package io.invest.iagent.financial.ingest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.financial.config.FinancialProperties;
import io.invest.iagent.financial.config.KeywordDictEntry;
import io.invest.iagent.financial.config.KeywordMetricConfig;
import io.invest.iagent.financial.config.KeywordMetricDef;
import io.invest.iagent.financial.model.CompanyDO;
import io.invest.iagent.financial.model.MetricCatalog;
import io.invest.iagent.financial.model.MetricDef;
import io.invest.iagent.financial.model.MetricSource;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.model.PeriodType;
import io.invest.iagent.financial.repository.FinancialRepository;
import io.invest.iagent.rag.chatting.Chatter;
import io.invest.iagent.rag.chunking.reader.DocumentReader;
import io.invest.iagent.rag.filing.model.FiscalPeriod;
import io.invest.iagent.rag.model.Document;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 关键字补充指标提取：futu API 不提供、且不便走 RAG 的指标（分红、回购等），
 * 直接在本地财报文件（PDF/HTML）中按关键字检索段落，取 top N 段落交 LLM 提取结构化数值。
 *
 * <p>与 {@link RagMetricExtractor} 的区别：不依赖 RAGFlow 知识库（构建/检索慢），
 * 只做本地文件读取 + 关键字匹配 + 一次 LLM 调用。
 *
 * <p>配置两层：
 * <ol>
 *   <li>{@code keyword-dict.yml}：按指标编码的简体/繁体/英文同义关键字字典，全公司共享；</li>
 *   <li>{@code keyword-metrics.yml}：公司维度配置（ticker → 指标列表），每条可再配
 *       公司特有的逗号分隔关键字与提取提示。</li>
 * </ol>
 *
 * <p>检索规则：扫描财报文本，以命中关键字的行为中心拼接相邻行（PDF 硬换行常把标签与
 * 数值拆到不同行），长度 ≤100 字保留整段，超过 100 字按逗号/分号拆子句、
 * 仅保留命中子句（及其紧跟的金额子句）；按命中关键字数、是否含金额/单位打分，
 * 取 top N（app.financial.keyword-top-snippets，默认 10）。
 * 之后按"期间 × 指标"各调一次 LLM，要求严格输出 JSON，按置信度阈值批量入库（来源 KEYWORD）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class KeywordMetricExtractor {

    /** 段落长度阈值：≤100 字保留整段，超过则按逗号/分号拆子句保留命中部分 */
    private static final int PARAGRAPH_MAX_CHARS = 100;
    /** 长段落按逗号/分号（中英文）拆子句 */
    private static final Pattern CLAUSE_SPLIT_RE = Pattern.compile("[,，;；]");
    /** 金额子句识别（含数字即可，兼容 1,234,567 / 1,234.5） */
    private static final Pattern AMOUNT_RE = Pattern.compile("\\d[\\d,\\.]*");

    @Autowired
    private FinancialRepository repository;

    @Autowired
    private FinancialProperties properties;

    @Autowired
    private MetricCatalog metricCatalog;

    @Autowired
    private KeywordMetricConfig metricConfig;

    @Autowired
    private List<KeywordDictEntry> keywordDict;

    @Autowired
    private List<DocumentReader> documentReaders;

    /** LLM 模块未启用/未配置时为 null，提取自动跳过 */
    @Autowired(required = false)
    private Chatter chatter;

    /**
     * 提取结果。
     */
    public record KeywordExtractResult(int extracted, List<String> warnings) {}

    /** 命中段落：保留文本、来源文件、相关性打分。 */
    private record Snippet(String text, Path file, int score) {}

    /**
     * 对指定公司执行关键字补充指标提取（best-effort，异常不抛出）。
     *
     * @param ticker  裸股票代码（BABA / 00700）
     * @param periods 待提取期间列表（如 [2025Q1, FY2025]），逐期 × 逐指标检索提取；null/空直接跳过
     */
    public KeywordExtractResult extract(String ticker, List<String> periods) {
        List<String> warnings = new ArrayList<>();
        if (!properties.isKeywordExtractEnabled()) {
            return new KeywordExtractResult(0, warnings);
        }
        if (chatter == null) {
            warnings.add("关键字补充指标提取跳过：LLM 模块未启用。");
            return new KeywordExtractResult(0, warnings);
        }
        List<KeywordMetricDef> defines = resolveDefs(ticker);
        if (defines.isEmpty()) {
            // 公司未配置关键字指标，静默跳过
            return new KeywordExtractResult(0, warnings);
        }

        List<String> targetPeriods = normalizePeriods(periods, warnings);
        if (targetPeriods.isEmpty()) {
            warnings.add("关键字补充指标提取跳过：无有效期间（形如 2025Q1、FY2025）。");
            return new KeywordExtractResult(0, warnings);
        }

        int fyeMonth = resolveFyeMonth(ticker);
        Path reportBaseDir = Path.of(properties.getReportBaseDir()).toAbsolutePath();
        List<Path> allFiles = SegmentIngestor.discoverReportFiles(reportBaseDir, ticker);
        if (allFiles.isEmpty()) {
            warnings.add("关键字补充指标提取跳过：" + reportBaseDir + " 下无 " + ticker + " 的 PDF/HTML 财报。");
            return new KeywordExtractResult(0, warnings);
        }

        // 指标编码 → 检索关键字（公司配置逗号拆分 + 字典简/繁/英同义词自动并入）
        Map<String, List<String>> keywordsByCode = buildKeywords(defines);

        // 文件全文缓存：同一文件跨期间/跨指标只读一次（PDF 解析较慢）
        Map<Path, String> textCache = new HashMap<>();

        int totalExtracted = 0;
        List<String> metricCodes = defines.stream().map(KeywordMetricDef::getCode).toList();
        for (String period : targetPeriods) {
            // 当期报告 + 含该期对比表的上年报告；文件名不可解析期间的保守保留
            List<Path> periodFiles = SegmentIngestor.filterReportsByPeriods(
                    allFiles, Set.of(period), fyeMonth);
            if (periodFiles.isEmpty()) {
                continue;
            }

            // 已有更高优先级来源（FUTU_API/DERIVED/RAG/SEGMENT_PARSE）值的指标跳过，KEYWORD 来源重提
            List<MetricValueDO> existing = repository.queryMetrics(ticker, metricCodes, List.of(period));
            List<MetricValueDO> periodRows = new ArrayList<>();
            for (KeywordMetricDef def : defines) {
                try {
                    boolean has = existing.stream().anyMatch(v ->
                            def.getCode().equals(v.getMetricCode()) && v.getValue() != null
                                    && !MetricSource.KEYWORD.name().equals(v.getSource()));
                    if (has) {
                        continue;
                    }

                    List<String> keywords = keywordsByCode.getOrDefault(def.getCode(), List.of());
                    if (keywords.isEmpty()) {
                        continue;
                    }

                    List<Snippet> snippets = searchSnippets(periodFiles, keywords, textCache,
                            properties.getKeywordTopSnippets());
                    if (snippets.isEmpty()) {
                        log.info("Keyword extract: no paragraph for {} {} {}",
                                ticker, period, def.getCode());
                        continue;
                    }

                    String systemPrompt = "你是严谨的财务数据提取助手。只能依据用户提供的财报原文片段提取数值，"
                            + "片段中没有明确披露时返回 found=false，绝不推测、不用片段外数据、不跨期拼凑。";
                    String userPrompt = buildPrompt(ticker, period, def, keywords, snippets);
                    String response = chatter.chat(systemPrompt, userPrompt);
                    // OllamaChatter 异常时会原样返回 userPrompt，视为失败
                    if (response == null || response.isBlank() || response.equals(userPrompt)) {
                        continue;
                    }
                    MetricValueDO row = parseResponse(ticker, period, def, response, snippets.get(0).file());
                    if (row != null) {
                        periodRows.add(row);
                    }
                } catch (Exception e) {
                    // 单个指标失败不影响同期间其他指标
                    log.warn("Keyword extract failed for {} {} {}: {}",
                            ticker, period, def.getCode(), e.getMessage());
                }
            }
            if (!periodRows.isEmpty()) {
                repository.batchUpsertMetrics(periodRows);
                totalExtracted += periodRows.size();
                log.info("Keyword extract: {} {} 提取 {} 个指标", ticker, period, periodRows.size());
            }
        }

        repository.recordBatch(ticker, "KEYWORD", totalExtracted > 0 ? "SUCCESS" : "PARTIAL",
                String.join(",", targetPeriods), "extracted=" + totalExtracted);
        return new KeywordExtractResult(totalExtracted, warnings);
    }

    /** 取公司维度的关键字指标配置（ticker 原样匹配失败时按大写重试）。 */
    private List<KeywordMetricDef> resolveDefs(String ticker) {
        Map<String, List<KeywordMetricDef>> companies = metricConfig.getCompanies();
        if (companies == null || companies.isEmpty()) {
            return List.of();
        }
        List<KeywordMetricDef> defs = companies.get(ticker);
        if (defs == null) {
            defs = companies.get(ticker.toUpperCase(Locale.ROOT));
        }
        return defs == null ? List.of() : defs;
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
                warnings.add("关键字补充指标提取跳过无法识别的期间: " + p + "（形如 2025Q1、FY2025）");
                continue;
            }
            set.add(fp);
        }
        return set.stream().map(FiscalPeriod::canonical).toList();
    }

    /** 组装每个指标的检索关键字：公司配置（逗号分隔）+ 字典中同编码的简/繁/英同义词，去重保序。 */
    private Map<String, List<String>> buildKeywords(List<KeywordMetricDef> defs) {
        Map<String, KeywordDictEntry> dictByCode = new HashMap<>();
        if (keywordDict != null) {
            for (KeywordDictEntry e : keywordDict) {
                if (e.getCode() != null) {
                    dictByCode.put(e.getCode(), e);
                }
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (KeywordMetricDef def : defs) {
            LinkedHashSet<String> kws = new LinkedHashSet<>();
            if (def.getKeywords() != null) {
                for (String k : def.getKeywords().split(",")) {
                    if (!k.isBlank()) {
                        kws.add(k.trim());
                    }
                }
            }
            KeywordDictEntry dict = dictByCode.get(def.getCode());
            if (dict != null) {
                addAllKeywords(kws, dict.getCn());
                addAllKeywords(kws, dict.getTw());
                addAllKeywords(kws, dict.getEn());
            }
            result.put(def.getCode(), new ArrayList<>(kws));
        }
        return result;
    }

    private static void addAllKeywords(Set<String> sink, List<String> values) {
        if (values != null) {
            values.stream().filter(k -> k != null && !k.isBlank())
                    .map(String::trim).forEach(sink::add);
        }
    }

    /** 命中关键字行向上拼接的行数（表格中关键字上方可能有列头/期间） */
    private static final int CONTEXT_LINES_BEFORE = 1;
    /** 命中关键字行向下拼接的行数（"资本开支数据："换行后数值常落在下 1-2 行） */
    private static final int CONTEXT_LINES_AFTER = 2;

    /**
     * 在财报全文中检索命中关键字的段落。PDF/HTML 提取文本按版面宽度硬换行，
     * 关键字与其数值可能落在相邻行（如"资本开支数据："另起一行才是金额），
     * 故以命中行为中心拼接相邻行（上 {@value #CONTEXT_LINES_BEFORE} 行 + 下
     * {@value #CONTEXT_LINES_AFTER} 行）作为候选段落；≤100 字保留整段，
     * 超过的拆逗号子句保留命中部分；按相关性打分后取 topN，文本去重。
     */
    private List<Snippet> searchSnippets(List<Path> files, List<String> keywords,
                                         Map<Path, String> textCache, int topN) {
        List<Snippet> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path file : files) {
            String text = textCache.computeIfAbsent(file, this::readFileText);
            if (StringUtils.isBlank(text)) {
                continue;
            }
            String[] lines = text.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || !containsKeyword(line, keywords)) {
                    continue;
                }
                String para = joinLines(lines, i - CONTEXT_LINES_BEFORE, i + CONTEXT_LINES_AFTER);
                String snippet = para.length() <= PARAGRAPH_MAX_CHARS
                        ? para
                        : extractClauses(para, keywords);
                if (StringUtils.isBlank(snippet)) {
                    continue;
                }
                int score = scoreSnippet(snippet, keywords);
                if (score > 0 && seen.add(snippet)) {
                    hits.add(new Snippet(snippet, file, score));
                }
            }
        }
        hits.sort(Comparator.comparingInt(Snippet::score).reversed());
        return hits.size() > topN ? new ArrayList<>(hits.subList(0, topN)) : hits;
    }

    /** 拼接 [from, to] 行号区间内的非空行（单行 trim，行间以空格连接）。 */
    private static String joinLines(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int j = Math.max(0, from); j < Math.min(lines.length, to + 1); j++) {
            String l = lines[j].trim();
            if (l.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(l);
        }
        return sb.toString();
    }

    /**
     * 长段落拆子句：保留命中关键字的子句；紧跟关键句之后的短金额子句（财报表格中金额常与
     * 说明分列）一并保留，避免只剩文字没有数值。
     */
    private String extractClauses(String paragraph, List<String> keywords) {
        String[] clauses = CLAUSE_SPLIT_RE.split(paragraph);
        List<String> kept = new ArrayList<>();
        boolean prevKept = false;
        for (String raw : clauses) {
            String c = raw.trim();
            if (c.isEmpty()) {
                continue;
            }
            boolean kwHit = containsKeyword(c, keywords);
            boolean amountAfterKw = prevKept && c.length() <= 40 && AMOUNT_RE.matcher(c).find();
            if (kwHit || amountAfterKw) {
                kept.add(c);
            }
            prevKept = kwHit;
        }
        return kept.isEmpty() ? null : String.join("，", kept);
    }

    /** 相关性打分：命中不同关键字数（权重 3）+ 命中次数（上限 5）+ 含金额 3 分 + 含单位 1 分。 */
    private int scoreSnippet(String snippet, List<String> keywords) {
        String lower = snippet.toLowerCase(Locale.ROOT);
        int distinct = 0;
        int occurrences = 0;
        for (String kw : keywords) {
            int c = countOccurrences(lower, kw.toLowerCase(Locale.ROOT));
            if (c > 0) {
                distinct++;
                occurrences += c;
            }
        }
        if (distinct == 0) {
            return 0;
        }
        int score = distinct * 3 + Math.min(occurrences, 5);
        if (AMOUNT_RE.matcher(snippet).find()) {
            score += 3;
        }
        if (snippet.matches(".*(千|百萬|百万|萬|万|million|thousand|billion).*")) {
            score += 1;
        }
        return score;
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static boolean containsKeyword(String text, List<String> keywords) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (!kw.isBlank() && lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 按扩展名选择 DocumentReader 读取财报全文；失败返回空串（不中断整体提取）。 */
    private String readFileText(Path file) {
        try {
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
            DocumentReader reader = documentReaders.stream()
                    .filter(r -> r.supportTypes() != null && r.supportTypes().contains(ext))
                    .findFirst().orElse(null);
            if (reader == null) {
                log.warn("Keyword extract: 无 {} 文件读取器: {}", ext, file);
                return "";
            }
            Document doc = Document.builder().filePath(file.toAbsolutePath().toString()).build();
            String text = reader.read(doc);
            return text == null ? "" : text;
        } catch (Exception e) {
            log.warn("Keyword extract: 读取财报失败 {}: {}", file, e.getMessage());
            return "";
        }
    }

    /** 构造单个指标的结构化提取提示（要求模型仅输出 JSON）。 */
    private String buildPrompt(String ticker, String period, KeywordMetricDef def,
                               List<String> keywords, List<Snippet> snippets) {
        MetricDef metricDef = metricCatalog.get(def.getCode());
        String name = metricDef != null ? metricDef.getNameCn() : def.getCode();
        StringBuilder sb = new StringBuilder();
        sb.append("请仅依据下列财报原文片段，提取公司 ").append(ticker).append(" 在 ").append(period)
                .append(" ").append(periodScope(period)).append("的指标 ").append(def.getCode())
                .append("（").append(name).append("）的数值。\n");
        sb.append("检索关键字：").append(String.join("、", keywords)).append("\n");
        if (def.getHint() != null && !def.getHint().isBlank()) {
            sb.append("提示：").append(def.getHint()).append("\n");
        }
        sb.append("原文片段（按相关性排序，均来自该公司财报，可能含当期数与上年同期对比数）：\n");
        for (int i = 0; i < snippets.size(); i++) {
            sb.append(i + 1).append(". 【").append(snippets.get(i).file().getFileName()).append("】")
                    .append(snippets.get(i).text()).append("\n");
        }
        sb.append("""
                严格要求：
                1. 只能使用片段中明确披露的数据，不得推测或计算（片段没有就 found=false）；
                2. 注意区分当期数与上年同期/前期对比数，取要求期间的当期发生额；
                3. 金额一律换算为"百万"单位（原文为千元/千港元/千人民币则除以1000，为万元则除以100，
                   为十亿美元则乘以1000；原文已是百万/百萬则不变）；
                4. 分红、回购为现金流量表/权益变动表中该期间的实际发生额；
                5. 只输出一个 JSON 对象，不要输出任何解释文字，格式：
                {"METRIC_CODE": {"found": true, "value": 123.45, "unit": "million", "confidence": 90, "evidence": "原文短句"}}
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

    /** 解析模型 JSON 回答，按置信度阈值产出 KEYWORD 来源指标行；未找到/置信度不足/解析失败返回 null。 */
    private MetricValueDO parseResponse(String ticker, String period, KeywordMetricDef def,
                                        String text, Path primaryFile) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JSONObject obj;
        try {
            obj = JSON.parseObject(text.substring(start, end + 1));
        } catch (Exception e) {
            log.debug("Keyword extract JSON 解析失败: {}", e.getMessage());
            return null;
        }

        JSONObject cell = obj.getJSONObject(def.getCode());
        if (cell == null) {
            // 兼容模型把 code 小写输出
            cell = obj.getJSONObject(def.getCode().toLowerCase(Locale.ROOT));
        }
        if (cell == null || !cell.getBooleanValue("found", false)) {
            return null;
        }
        BigDecimal value = cell.getBigDecimal("value");
        if (value == null) {
            return null;
        }
        Integer confidence = cell.getInteger("confidence");
        if (confidence != null && confidence < def.getMinConfidence()) {
            log.debug("Keyword extract 置信度不足: {} {} {} = {} ({})",
                    ticker, period, def.getCode(), value, confidence);
            return null;
        }

        return MetricValueDO.builder()
                .ticker(ticker)
                .fiscalPeriod(period)
                .periodType(periodTypeOf(period).name())
                .metricCode(def.getCode())
                .value(normalizeUnit(value, cell.getString("unit")))
                .unit("million")
                .source(MetricSource.KEYWORD.name())
                .confidence(confidence)
                .documentId(primaryFile == null ? null : primaryFile.getFileName().toString())
                .build();
    }

    /** 模型声称的单位归一到百万。 */
    private BigDecimal normalizeUnit(BigDecimal value, String unit) {
        if (unit == null) {
            return value;
        }
        String u = unit.toLowerCase(Locale.ROOT);
        if (u.contains("million") || u.contains("百万") || u.contains("百萬")) {
            return value;
        }
        if (u.contains("thousand") || u.contains("千")) {
            return value.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        }
        if (u.contains("billion") || u.contains("十亿")) {
            return value.multiply(BigDecimal.valueOf(1000));
        }
        if (u.contains("万") || u.contains("萬")) {
            // 万元/萬元 → 百万元
            return value.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        }
        return value;
    }

    /** 取公司财年结束月（三大表采集时写入 fin_company），查询失败默认 12（自然年）。 */
    private int resolveFyeMonth(String ticker) {
        try {
            CompanyDO company = repository.findCompany(ticker);
            if (company != null && company.getFyEndMonth() > 0) {
                return company.getFyEndMonth();
            }
        } catch (Exception e) {
            log.debug("查询公司财年结束月失败，按自然年处理: ticker={}, {}", ticker, e.getMessage());
        }
        return 12;
    }
}
