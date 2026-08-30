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
import io.invest.iagent.rag.filing.retrieve.FilingTagKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 补充指标提取：futu API 不提供的指标（SBC、Adjusted EBITDA、Non-GAAP 净利润、
 * 分红、回购）从财报知识库中按年度报告提取。复用 filing RAG 管线
 * （混合检索 + 重排 + LLM 合成），要求模型返回结构化 JSON，按置信度阈值入库。
 *
 * <p>仅处理 FY 期间：年报对这五类指标披露最完整，且规避港股/A股中报累计口径与
 * 单季口径标签不一致的问题。知识库未构建时跳过并给出提示。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class RagMetricExtractor {

    /** RAG 提取最多回溯的年报期数 */
    private static final int MAX_FY_PERIODS = 4;

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
     */
    public RagExtractResult extract(String ticker) {
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

        // 取最近 N 个 FY 期间
        List<String> fyPeriods = repository.findMetricPeriods(ticker).stream()
                .filter(p -> p.startsWith("FY"))
                .sorted()
                .toList();
        if (fyPeriods.isEmpty()) {
            warnings.add("RAG 补充指标提取跳过：库中尚无年报期间数据，请先采集三大表。");
            return new RagExtractResult(0, warnings);
        }
        fyPeriods = fyPeriods.size() > MAX_FY_PERIODS
                ? fyPeriods.subList(fyPeriods.size() - MAX_FY_PERIODS, fyPeriods.size())
                : fyPeriods;

        int totalExtracted = 0;
        boolean kbMissing = false;
        for (String period : fyPeriods) {
            try {
                // 已有高优先级来源（FUTU_API/DERIVED）或已提取过 RAG 值的指标跳过
                List<MetricValueDO> existing = repository.queryMetrics(
                        ticker, extraMetrics.stream().map(RagExtraMetric::getCode).toList(), List.of(period));
                List<RagExtraMetric> missing = new ArrayList<>();
                for (RagExtraMetric m : extraMetrics) {
                    boolean has = existing.stream().anyMatch(v ->
                            m.getCode().equals(v.getMetricCode()) && v.getValue() != null
                                    && !MetricSource.RAG.name().equals(v.getSource()));
                    if (!has) {
                        missing.add(m);
                    }
                }
                if (missing.isEmpty()) {
                    continue;
                }

                String question = buildQuestion(ticker, period, missing);
                FilingAnswer answer = filingQaService.ask(question, ticker, period, properties.getRagTopK());
                List<FilingChunk> chunks = answer.getChunks();
                if (chunks == null || chunks.isEmpty()) {
                    // 第一个期间就检索不到片段，基本可判定知识库未构建
                    kbMissing = true;
                    log.info("RAG extract: no chunks for {} {}，知识库可能未构建", ticker, period);
                    break;
                }
                if (answer.getChatResponse() == null || answer.getChatResponse().isBlank()) {
                    continue;
                }

                List<MetricValueDO> parsed = parseResponse(ticker, period, missing, answer);
                if (!parsed.isEmpty()) {
                    repository.batchUpsertMetrics(parsed);
                    totalExtracted += parsed.size();
                    log.info("RAG extract: {} {} 提取 {} 个指标", ticker, period, parsed.size());
                }
            } catch (Exception e) {
                log.warn("RAG extract failed for {} {}: {}", ticker, period, e.getMessage());
            }
        }

        if (kbMissing) {
            warnings.add("RAG 补充指标提取跳过：财报知识库未检索到片段。请先调用 filing_kb_build 构建 "
                    + ticker + " 的财报知识库后重试 financial_data_build。");
        }
        repository.recordBatch(ticker, "RAG", totalExtracted > 0 ? "SUCCESS" : "PARTIAL",
                String.join(",", fyPeriods), "extracted=" + totalExtracted);
        return new RagExtractResult(totalExtracted, warnings);
    }

    /** 构造结构化提取问题（要求模型仅输出 JSON）。 */
    private String buildQuestion(String ticker, String period, List<RagExtraMetric> metrics) {
        StringBuilder sb = new StringBuilder();
        sb.append("请从检索到的财报片段中，提取公司 ").append(ticker).append(" 在 ").append(period)
                .append(" 财年（年度报告，全年累计口径）的下列财务指标数值：\n");
        for (RagExtraMetric m : metrics) {
            MetricDef def = metricCatalog.get(m.getCode());
            String name = def != null ? def.getNameCn() : m.getCode();
            sb.append("- ").append(m.getCode()).append("（").append(name);
            if (m.getAliases() != null && !m.getAliases().isEmpty()) {
                sb.append("；同义词：").append(String.join("、", m.getAliases()));
            }
            sb.append("）");
            if (m.getHint() != null && !m.getHint().isBlank()) {
                sb.append("。提示：").append(m.getHint());
            }
            sb.append("\n");
        }
        sb.append("""
                严格要求：
                1. 只能使用检索片段中明确披露的数据，不得推测或计算（片段没有就 found=false）；
                2. 金额一律换算为"百万"单位（原文为千元则除以1000，为十亿美元则乘以1000）；
                3. 分红、回购为现金流量表/权益变动表中的全年实际发生额；
                4. 只输出一个 JSON 对象，不要输出任何解释文字，格式：
                {"METRIC_CODE": {"found": true, "value": 123.45, "unit": "million", "confidence": 90, "evidence": "原文短句"}, ...}
                confidence 为 0-100 的整数，表达你对数值与口径的把握。
                """);
        return sb.toString();
    }

    /** 解析模型 JSON 回答，按置信度阈值产出 RAG 来源指标行。 */
    private List<MetricValueDO> parseResponse(String ticker, String period,
                                              List<RagExtraMetric> metrics, FilingAnswer answer) {
        List<MetricValueDO> out = new ArrayList<>();
        String text = answer.getChatResponse();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return out;
        }
        JSONObject obj;
        try {
            obj = JSON.parseObject(text.substring(start, end + 1));
        } catch (Exception e) {
            log.debug("RAG extract JSON 解析失败: {}", e.getMessage());
            return out;
        }

        // 溯源信息取首条片段
        List<FilingChunk> chunks = answer.getChunks();
        String chunkId = null;
        String documentId = null;
        if (chunks != null && !chunks.isEmpty()) {
            FilingChunk first = chunks.get(0);
            chunkId = first.getChunkId();
            Map<String, String> tags = first.getTags();
            if (tags != null) {
                documentId = tags.get(FilingTagKeys.DOCUMENT_ID);
            }
        }

        for (RagExtraMetric m : metrics) {
            JSONObject cell = obj.getJSONObject(m.getCode());
            if (cell == null) {
                // 兼容模型把 code 小写输出
                cell = obj.getJSONObject(m.getCode().toLowerCase());
            }
            if (cell == null || !cell.getBooleanValue("found", false)) {
                continue;
            }
            BigDecimal value = cell.getBigDecimal("value");
            if (value == null) {
                continue;
            }
            Integer confidence = cell.getInteger("confidence");
            int min = m.getMinConfidence();
            if (confidence != null && confidence < min) {
                log.debug("RAG extract 置信度不足: {} {} {} = {} ({})",
                        ticker, period, m.getCode(), value, confidence);
                continue;
            }
            value = normalizeUnit(value, cell.getString("unit"));
            out.add(MetricValueDO.builder()
                    .ticker(ticker)
                    .fiscalPeriod(period)
                    .periodType(PeriodType.FY.name())
                    .metricCode(m.getCode())
                    .value(value)
                    .unit("million")
                    .source(MetricSource.RAG.name())
                    .confidence(confidence)
                    .chunkId(chunkId)
                    .documentId(documentId)
                    .build());
        }
        return out;
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
