package io.invest.iagent.financial.ingest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.financial.config.FinancialProperties;
import io.invest.iagent.financial.config.RagExtraMetric;
import io.invest.iagent.financial.model.MetricCatalog;
import io.invest.iagent.financial.model.MetricDef;
import io.invest.iagent.financial.model.MetricSource;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.model.PeriodType;
import io.invest.iagent.financial.repository.FinancialRepository;
import io.invest.iagent.rag.filing.FilingQaService;
import io.invest.iagent.rag.filing.model.FilingAnswer;
import io.invest.iagent.rag.filing.model.FilingChunk;
import io.invest.iagent.rag.filing.model.FiscalPeriod;
import io.invest.iagent.rag.filing.retrieve.FilingTagKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * RAG 补充指标提取：futu API 不提供的指标（SBC、Adjusted EBITDA、Non-GAAP 净利润、
 * 分红、回购）从财报知识库中提取。复用 filing RAG 管线
 * （混合检索 + 重排 + LLM 合成），要求模型返回结构化 JSON，按置信度阈值入库。
 *
 * <p>按调用方给定的期间列表逐期处理（季度/年度均可，如 [2025Q1, 2025Q2, 2025Q3, FY2025]）：
 * 每个期间 × 每个缺失指标各发起一次检索提问——检索标签只带该期间，问题只含单个指标，
 * 使检索更聚焦、模型只需判定一个指标，避免一次提问多指标相互干扰。
 * 知识库未构建时跳过并给出提示。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class RagMetricExtractor {

    @Autowired
    private FinancialRepository repository;

    @Autowired
    private FinancialProperties properties;

    @Autowired
    private MetricCatalog metricCatalog;

    @Autowired
    private List<RagExtraMetric> extraMetrics;

    /** filing RAG 模块未启用/未配置时为 null，提取自动跳过 */
    @Autowired(required = false)
    private FilingQaService filingQaService;

    /**
     * 提取结果。
     */
    public record RagExtractResult(int extracted, List<String> warnings) {}

    /**
     * 对指定公司执行 RAG 补充指标提取（best-effort，异常不抛出）。
     *
     * @param ticker  股票代码
     * @param periods 待提取期间列表（如 [2025Q1, 2025Q2, 2025Q3, FY2025]），
     *                逐期 × 逐指标检索提取（每个缺失指标单独一次提问）；null/空直接跳过
     */
    public RagExtractResult extract(String ticker, List<String> periods) {
        List<String> warnings = new ArrayList<>();
        if (!properties.isRagExtractEnabled()) {
            return new RagExtractResult(0, warnings);
        }
        if (filingQaService == null) {
            warnings.add("RAG 补充指标提取跳过：filing 知识库模块未启用。");
            return new RagExtractResult(0, warnings);
        }
        if (extraMetrics == null || extraMetrics.isEmpty()) {
            return new RagExtractResult(0, warnings);
        }

        // 规范化期间：解析为 canonical（2025Q1/FY2025），去重并按时间升序；无法识别的告警跳过
        List<String> targetPeriods = normalizePeriods(periods, warnings);
        if (targetPeriods.isEmpty()) {
            warnings.add("RAG 补充指标提取跳过：无有效期间（形如 2025Q1、FY2025）。");
            return new RagExtractResult(0, warnings);
        }

        List<String> metricCodes = extraMetrics.stream().map(RagExtraMetric::getCode).toList();
        int totalExtracted = 0;
        int attempted = 0;
        int noChunkAsks = 0;
        for (String period : targetPeriods) {
            // 已有高优先级来源（FUTU_API/DERIVED）值的指标跳过；RAG 来源或缺失的重新提取
            List<MetricValueDO> existing = repository.queryMetrics(
                    ticker, metricCodes, List.of(period));
            List<MetricValueDO> periodRows = new ArrayList<>();
            for (RagExtraMetric metric : extraMetrics) {
                try {
                    boolean has = existing.stream().anyMatch(v ->
                            metric.getCode().equals(v.getMetricCode()) && v.getValue() != null
                                    && !MetricSource.RAG.name().equals(v.getSource()));
                    if (has) {
                        continue;
                    }

                    // 一个周期一个指标一次提问：检索标签只带当前期间，问题只含当前指标
                    attempted++;
                    String question = buildQuestion(ticker, period, metric);
                    FilingAnswer answer = filingQaService.ask(question, ticker, period, properties.getRagTopK());
                    List<FilingChunk> chunks = answer.getChunks();
                    if (chunks == null || chunks.isEmpty()) {
                        noChunkAsks++;
                        log.info("RAG extract: no chunks for {} {} {}，知识库可能未构建该期间",
                                ticker, period, metric.getCode());
                        continue;
                    }
                    if (answer.getChatResponse() == null || answer.getChatResponse().isBlank()) {
                        continue;
                    }

                    MetricValueDO row = parseResponse(ticker, period, metric, answer);
                    if (row != null) {
                        periodRows.add(row);
                    }
                } catch (Exception e) {
                    // 单个指标失败不影响同期间其他指标
                    log.warn("RAG extract failed for {} {} {}: {}",
                            ticker, period, metric.getCode(), e.getMessage());
                }
            }
            if (!periodRows.isEmpty()) {
                repository.batchUpsertMetrics(periodRows);
                totalExtracted += periodRows.size();
                log.info("RAG extract: {} {} 提取 {} 个指标", ticker, period, periodRows.size());
            }
        }

        // 所有提问都检索不到片段，基本可判定知识库未构建
        if (attempted > 0 && noChunkAsks == attempted) {
            warnings.add("RAG 补充指标提取跳过：财报知识库未检索到片段。请先调用 filing_kb_build 构建 "
                    + ticker + " 的财报知识库后重试 financial_data_build。");
        }
        repository.recordBatch(ticker, "RAG", totalExtracted > 0 ? "SUCCESS" : "PARTIAL",
                String.join(",", targetPeriods), "extracted=" + totalExtracted);
        return new RagExtractResult(totalExtracted, warnings);
    }

    /** 规范化期间列表：解析为 canonical 形式，去重并按时间升序；无法识别的告警跳过。 */
    private List<String> normalizePeriods(List<String> periods, List<String> warnings) {
        if (periods == null || periods.isEmpty()) {
            return List.of();
        }
        TreeSet<FiscalPeriod> set = new TreeSet<>();
        for (String p : periods) {
            FiscalPeriod fp = FiscalPeriod.parse(p);
            if (fp == null) {
                warnings.add("RAG 补充指标提取跳过无法识别的期间: " + p + "（形如 2025Q1、FY2025）");
                continue;
            }
            set.add(fp);
        }
        return set.stream().map(FiscalPeriod::canonical).toList();
    }

    /** 构造单个指标的结构化提取问题（要求模型仅输出 JSON）。 */
    private String buildQuestion(String ticker, String period, RagExtraMetric m) {
        MetricDef def = metricCatalog.get(m.getCode());
        String name = def != null ? def.getNameCn() : m.getCode();
        StringBuilder sb = new StringBuilder();
        sb.append("请从检索到的财报片段中，提取公司 ").append(ticker).append(" 在 ").append(period)
                .append(" ").append(periodScope(period)).append("的下列财务指标数值：\n");
        sb.append("- ").append(m.getCode()).append("（").append(name);
        if (m.getAliases() != null && !m.getAliases().isEmpty()) {
            sb.append("；同义词：").append(String.join("、", m.getAliases()));
        }
        sb.append("）");
        if (m.getHint() != null && !m.getHint().isBlank()) {
            sb.append("。提示：").append(m.getHint());
        }
        sb.append("\n");
        sb.append("""
                严格要求：
                1. 只能使用检索片段中明确披露的数据，不得推测或计算（片段没有就 found=false）；
                2. 金额一律换算为"百万"单位（原文为千元则除以1000，为十亿美元则乘以1000）；
                3. 分红、回购为现金流量表/权益变动表中该期间的实际发生额；
                4. 只输出一个 JSON 对象，不要输出任何解释文字，格式：
                {"METRIC_CODE": {"found": true, "value": 123.45, "unit": "million", "confidence": 90, "evidence": "原文短句"}, ...}
                confidence 为 0-100 的整数，表达你对数值与口径的把握。
                """);
        return sb.toString();
    }

    /** 期间口径描述：FY 年报全年累计；H 中报年内累计；Q 季报当季单季。 */
    private String periodScope(String period) {
        FiscalPeriod fp = FiscalPeriod.parse(period);
        if (fp != null) {
            if (fp.ordinal() == 5) {
                return "财年（年度报告，全年累计口径）";
            }
            if (fp.canonical().contains("H")) {
                return "中期（半年度报告，年内累计口径）";
            }
        }
        return "季度（季度报告，该季度单季口径）";
    }

    /** 根据期间标签推断存储口径：FY→FY，H1/H2→CUMULATIVE，Qn→SINGLE_Q。 */
    private PeriodType periodTypeOf(String period) {
        FiscalPeriod fp = FiscalPeriod.parse(period);
        if (fp == null) {
            return PeriodType.SINGLE_Q;
        }
        if (fp.ordinal() == 5) {
            return PeriodType.FY;
        }
        if (fp.canonical().contains("H")) {
            return PeriodType.CUMULATIVE;
        }
        return PeriodType.SINGLE_Q;
    }

    /** 解析模型 JSON 回答，按置信度阈值产出 RAG 来源指标行；未找到/置信度不足/解析失败返回 null。 */
    private MetricValueDO parseResponse(String ticker, String period,
                                        RagExtraMetric m, FilingAnswer answer) {
        String text = answer.getChatResponse();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JSONObject obj;
        try {
            obj = JSON.parseObject(text.substring(start, end + 1));
        } catch (Exception e) {
            log.debug("RAG extract JSON 解析失败: {}", e.getMessage());
            return null;
        }

        JSONObject cell = obj.getJSONObject(m.getCode());
        if (cell == null) {
            // 兼容模型把 code 小写输出
            cell = obj.getJSONObject(m.getCode().toLowerCase());
        }
        if (cell == null || !cell.getBooleanValue("found", false)) {
            return null;
        }
        BigDecimal value = cell.getBigDecimal("value");
        if (value == null) {
            return null;
        }
        Integer confidence = cell.getInteger("confidence");
        if (confidence != null && confidence < m.getMinConfidence()) {
            log.debug("RAG extract 置信度不足: {} {} {} = {} ({})",
                    ticker, period, m.getCode(), value, confidence);
            return null;
        }

        // 溯源信息取首条片段
        String chunkId = null;
        String documentId = null;
        List<FilingChunk> chunks = answer.getChunks();
        if (chunks != null && !chunks.isEmpty()) {
            FilingChunk first = chunks.get(0);
            chunkId = first.getChunkId();
            Map<String, String> tags = first.getTags();
            if (tags != null) {
                documentId = tags.get(FilingTagKeys.DOCUMENT_ID);
            }
        }

        return MetricValueDO.builder()
                .ticker(ticker)
                .fiscalPeriod(period)
                .periodType(periodTypeOf(period).name())
                .metricCode(m.getCode())
                .value(normalizeUnit(value, cell.getString("unit")))
                .unit("million")
                .source(MetricSource.RAG.name())
                .confidence(confidence)
                .chunkId(chunkId)
                .documentId(documentId)
                .build();
    }

    /** 模型声称的单位归一到百万。 */
    private BigDecimal normalizeUnit(BigDecimal value, String unit) {
        if (unit == null) {
            return value;
        }
        String u = unit.toLowerCase();
        if (u.contains("thousand") || u.contains("千")) {
            return value.divide(BigDecimal.valueOf(1000), 6, java.math.RoundingMode.HALF_UP);
        }
        if (u.contains("billion") || u.contains("十亿")) {
            return value.multiply(BigDecimal.valueOf(1000));
        }
        return value;
    }
}
