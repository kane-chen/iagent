package io.invest.iagent.financial.ingest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.financial.config.prop.FinancialProperties;
import io.invest.iagent.financial.config.prop.KeywordDictEntry;
import io.invest.iagent.financial.config.prop.KeywordMetricConfig;
import io.invest.iagent.financial.config.prop.KeywordMetricDef;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 * <p>检索规则（纯关键字/规则，<b>不调用 LLM</b>）：扫描财报文本定位命中关键字的行，
 * PDF/HTML 硬换行常把一句话拆到多行、表格中标签与数值分列，故摘取片段时保留完整语义——
 * 散文行以命中行为中心向上/下扩展到句子边界（句首的报告期间状语、句末的上年同期对比数都不切掉），
 * 表格行整行保留并附上最近的年份表头；片段长度放开到 {@value #MAX_SNIPPET_CHARS} 字，
 * 仅极端超长时在句子/子句边界兜底截断。按命中关键字数、是否含金额/单位打分，
 * 取 top N（app.financial.keyword-top-snippets，默认 10）。
 * 之后按"期间 × 指标"各调一次 LLM，要求严格输出 JSON，按置信度阈值批量入库（来源 KEYWORD）。
 *
 * <p>数值口径：财报片段可能以单季（three months ended）、年初至今累计（six/nine months ended，
 * 如港股半年报/三季报的股份回购）或完整财年（year ended，如年报兜底四季度时的股份回购）口径披露。
 * LLM 逐项标注 periodType，入库时按数值<strong>实际披露口径</strong>落库（如目标 2025Q4、片段来自
 * 年报只披露全年值，则存 FY2025/FY，而非误标 2025Q4/SINGLE_Q），口径映射见 {@link #resolveStorageKey}。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class KeywordMetricExtractor {

    /** 表格行单元格分隔符：HtmlDocumentReader 把同一 <tr> 的各单元格以 " | " 连接为一行 */
    private static final String TABLE_CELL_SEP = " | ";
    /** 片段最大字符数：放开早期 100 字限制，完整保留命中句；仅在极端超长时兜底截断 */
    private static final int MAX_SNIPPET_CHARS = 1000;
    /** 以命中行为中心向上/下最多扩展的物理行数（PDF/HTML 硬换行下一句话常跨多行） */
    private static final int MAX_EXTEND_LINES = 12;
    /** 表格行向上连续查找表头（列期间）的最大行数 */
    private static final int TABLE_HEADER_LOOKBACK = 4;
    /** 年份识别（表头列通常是 2024 / 2025 / March 31, 2026 等） */
    private static final Pattern YEAR_RE = Pattern.compile("(?:19|20)\\d{2}");
    /** 超长兜底时可安全切断的句子/子句边界（句末标点 + 中英文逗号/分号） */
    private static final Pattern BOUNDARY_RE = Pattern.compile("[。！？!?.;；，,]\\s*");
    /**
     * 句末标点（零宽尾视：只消费到标点本身及其后紧随的一个右括号/引号，不吞下一句首字母）：
     * 中文 。！？ 直接视为句末；英文句号后（允许隔着一个右括号/引号）须为空白或文本结尾，
     * 规避小数点（1,234.5）与缩写（e.g. the）误判。同时用于"向下扩展是否收尾"判断与句末裁剪。
     */
    private static final Pattern SENTENCE_END_RE =
            Pattern.compile("[。！？!?]|\\.[)）”\"]?(?=\\s|$)");
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

    /** 命中段落：保留文本、来源文件、命中行号（1 基，证据溯源用）、相关性打分。 */
    private record Snippet(String text, Path file, int line, int score) {}

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
        List<KeywordMetricDef> defines = resolveDefines(ticker);
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

            // 已有 KEYWORD 来源值的指标跳过（避免重复调用 LLM），其他来源（FUTU_API/RAG 等）不跳过；
            // 查询期间需含口径映射后的别名（季度目标在财年末季时可能已写入 FY 全年值，反之亦然）
            List<MetricValueDO> existing = repository.queryMetrics(ticker, metricCodes,
                    candidateStoragePeriods(period, fyeMonth));
            List<MetricValueDO> periodRows = new ArrayList<>();
            for (KeywordMetricDef define : defines) {
                try {
                    boolean has = existing.stream().anyMatch(v ->
                                       define.getCode().equals(v.getMetricCode())
                                    && v.getValue() != null
                                    && MetricSource.KEYWORD.name().equals(v.getSource()));
                    if (has) {
                        continue;
                    }

                    List<String> keywords = keywordsByCode.getOrDefault(define.getCode(), List.of());
                    if (keywords.isEmpty()) {
                        continue;
                    }

                    List<Snippet> snippets = searchSnippets(periodFiles, keywords, textCache,
                            properties.getKeywordTopSnippets());
                    if (snippets.isEmpty()) {
                        log.info("Keyword extract: no paragraph for {} {} {}",
                                ticker, period, define.getCode());
                        continue;
                    }

                    String systemPrompt = "你是严谨的财务数据提取助手。只能依据用户提供的财报原文片段提取数值，"
                            + "片段中没有明确披露时返回 found=false，绝不推测、不用片段外数据、不跨期拼凑。";
                    String userPrompt = buildPrompt(ticker, period, define, keywords, snippets);
                    String response = chatter.chat(systemPrompt, userPrompt);
                    // OllamaChatter 异常时会原样返回 userPrompt，视为失败
                    if (response == null || response.isBlank() || response.equals(userPrompt)) {
                        continue;
                    }
                    List<MetricValueDO> rows = parseResponse(
                            ticker, period, define, response, snippets.get(0).file(), fyeMonth);
                    periodRows.addAll(rows);
                } catch (Exception e) {
                    // 单个指标失败不影响同期间其他指标
                    log.warn("Keyword extract failed for {} {} {}: {}",
                            ticker, period, define.getCode(), e.getMessage());
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
    private List<KeywordMetricDef> resolveDefines(String ticker) {
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

    /**
     * 在财报全文中检索命中关键字的片段。PDF/HTML 提取文本按版面宽度硬换行，一句话常跨多行，
     * 表格中标签与数值分列。为让交给 LLM 的片段"自成一段、语义完整"（含报告期间状语、金额、
     * 上年同期对比），不再按 100 字硬切、也不只保留含关键字的子句，而是按语义边界摘取
     *（详见 {@link #extractClauses}）；按相关性打分后取 topN，文本去重。
     */
    private List<Snippet> searchSnippets(List<Path> files, List<String> keywords,
                                         Map<Path, String> textCache, int topN) {
        // 关键字编译为单个交替正则（字面量、大小写不敏感）：行内命中检测与片段打分共用，
        // 一次 find() 扫描替代原先"逐关键字 contains + 逐关键字 indexOf 计数"的多遍匹配。
        Pattern kwRegex = buildKeywordRegex(keywords);
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
                if (line.isEmpty() || !kwRegex.matcher(line).find()) {
                    continue;
                }
                // 表格行整行保留并补年份表头；散文行扩展为完整句子——期间状语/对比数不被切掉
                String snippet = extractClauses(lines, i, kwRegex);
                if (StringUtils.isBlank(snippet)) {
                    continue;
                }
                int score = scoreSnippet(snippet, kwRegex);
                if (score > 0 && seen.add(snippet)) {
                    hits.add(new Snippet(snippet, file, i + 1, score));   // 行号 1 基
                }
            }
        }
        hits.sort(Comparator.comparingInt(Snippet::score).reversed());
        return hits.size() > topN ? new ArrayList<>(hits.subList(0, topN)) : hits;
    }

    /**
     * 把关键字列表编译为单个交替正则（{@code kw1|kw2|...}）。
     * 关键字按字面量处理（{@link Pattern#quote} 转义正则特殊字符），大小写不敏感；
     * 关键字全为空时返回永不匹配的正则（{@code (?!)}），避免空交替匹配任意位置。
     */
    private static Pattern buildKeywordRegex(List<String> keywords) {
        String alt = keywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(String::trim)
                .distinct()
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        return alt.isEmpty()
                ? Pattern.compile("(?!)")
                : Pattern.compile(alt, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * 摘取命中关键字的文本片段（纯规则，<b>检索阶段不调用 LLM</b>）。
     *
     * <p>财报文本经 PDF/HTML 提取后按版面宽度硬换行：一句话常跨多行，表格中标签与数值分列。
     * 早期实现把命中行上下拼 4 行、超过 100 字就按逗号拆子句、只保留含关键字的子句，结果会
     * 切掉句首的报告期间状语（"During the quarter ended June 30, 2026,"）与句末的上年同期
     * 对比（"compared to ... in the same quarter of 2025"），LLM 拿到残句无法判断数值归属期间。
     * 这里改为按语义边界摘取，保证每个片段"自成一段、可独立定值"：
     * <ul>
     *   <li>表格行（含 " | "）：整行保留（标签 | 当期数 | 上年同期数），并上溯最近的年份表头；</li>
     *   <li>散文行：以命中行为中心向上扩展到句首、向下扩展到句尾，拼出包含关键字的完整句子，
     *       超长时在句子/子句边界兜底截断（{@link #capSnippet}）。</li>
     * </ul>
     */
    private String extractClauses(String[] lines, int hit, Pattern kwRegex) {
        return lines[hit].trim().contains(TABLE_CELL_SEP)
                ? extractTableRow(lines, hit)
                : extractSentence(lines, hit, kwRegex);
    }

    /** 表格命中：整行保留（{@code 标签 | 当期数 | 上年同期数} 本就是一条完整记录），并附上最近年份表头。 */
    private String extractTableRow(String[] lines, int hit) {
        String row = lines[hit].trim();
        String header = findTableHeader(lines, hit);
        return header == null ? row : header + "\n" + row;
    }

    /** 千分位分组金额（12,345 / 1,234,567）：数据行特征；年份表头（2025/2026）无逗号分组。 */
    private static final Pattern GROUPED_AMOUNT_RE = Pattern.compile("\\d{1,3}(?:,\\d{3})+");

    /**
     * 向上连续（不跨非表格行）查找表头行：含年份（如 2025/2026、March 31, 2026）且<b>不含</b>
     * 千分位分组金额的即是列期间表头；含分组金额（38,676）的是数据行，继续上溯。
     */
    private static String findTableHeader(String[] lines, int hit) {
        for (int k = hit - 1; k >= Math.max(0, hit - TABLE_HEADER_LOOKBACK); k--) {
            String t = lines[k].trim();
            if (!t.contains(TABLE_CELL_SEP)) {
                break;   // 表头与数据行连续，遇到空行/散文说明已离开本表
            }
            if (YEAR_RE.matcher(t).find() && !GROUPED_AMOUNT_RE.matcher(t).find()) {
                return t;
            }
        }
        return null;
    }

    /**
     * 散文命中：以命中行为中心，向上扩展到句首、向下扩展到句尾，拼出包含关键字的<b>完整句子</b>。
     * 这样句首的报告期间状语与句末的上年同期对比都不会被切掉。
     */
    private String extractSentence(String[] lines, int hit, Pattern kwRegex) {
        // 向上：上一行是块边界（空行/标题/表格），或上一行已以句末标点结束（本句从其后开始）则停
        int start = hit;
        while (start > 0 && hit - start < MAX_EXTEND_LINES) {
            String prev = lines[start - 1];
            if (isBlockBoundary(prev) || lineEndsSentence(prev, lines[start])) {
                break;
            }
            start--;
        }
        // 向下：逐行纳入直到块边界或行数上限（PDF/HTML 硬换行下一句子可能跨多行）。
        // 窗口可能顺带包含相邻句子，下面以"命中行内关键字"为锚点精确切出目标句。
        int end = hit;
        while (end < lines.length - 1 && end - hit < MAX_EXTEND_LINES
                && !isBlockBoundary(lines[end + 1])) {
            end++;
        }
        // 拼接并记录命中行在结果文本中的起始偏移（作为目标关键字锚点）
        StringBuilder sb = new StringBuilder();
        int hitStart = -1;
        for (int j = start; j <= end; j++) {
            String line = lines[j].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            if (j == hit) {
                hitStart = sb.length();
            }
            sb.append(line);
        }
        return capSnippet(cutAnchorSentence(sb.toString(), hitStart, kwRegex), kwRegex);
    }

    /**
     * 以"命中行内的关键字"为锚点，从窗口文本中切出该关键字所在的<b>完整单句</b>：
     * 句首取锚点之前最后一个句末标点之后，句尾取锚点之后第一个句末标点。这样即便窗口顺带
     * 包含上一句（同一物理行上一句的 "facilities." 与本句 "In the year ended..." 并存）或下一句
     * 残片，也能精确剔除，只留语义自洽的目标句（含期间状语与对比数）。锚点缺失时退化为整段。
     */
    private static String cutAnchorSentence(String joined, int hitStart, Pattern kwRegex) {
        Matcher km = kwRegex.matcher(joined);
        int anchorBeg = -1;
        int anchorEnd = -1;
        while (km.find()) {
            if (km.start() >= hitStart) {      // 命中行（或其后）的第一个关键字即目标
                anchorBeg = km.start();
                anchorEnd = km.end();
                break;
            }
        }
        if (anchorBeg < 0) {
            return joined.trim();
        }
        // 句首：锚点之前最后一个句末标点之后的位置（跳过其后空白）
        int head = 0;
        Matcher sm = SENTENCE_END_RE.matcher(joined);
        while (sm.find() && sm.end() <= anchorBeg) {
            head = sm.end();
        }
        while (head < joined.length() && joined.charAt(head) == ' ') {
            head++;
        }
        // 句尾：锚点之后第一个句末标点；窗口未覆盖到（被行数上限截断）则取到文本末尾
        int tail = joined.length();
        Matcher tm = SENTENCE_END_RE.matcher(joined);
        if (tm.find(anchorEnd)) {
            tail = tm.end();
        }
        return joined.substring(head, tail).trim();
    }

    /** 块边界：空行、Markdown 标题（# 开头）、表格行——散文句子不跨越这些边界。 */
    private static boolean isBlockBoundary(String line) {
        String t = line.trim();
        return t.isEmpty() || t.startsWith("#") || t.contains(TABLE_CELL_SEP);
    }

    /**
     * 上一行是否已是一句之末（则当前行另起新句，无需向上并入）。
     * 中文 。！？ 与英文 ! ? 行尾即句末；英文句号需下一行首字符为大写/引号/中文才认定，
     * 否则只是 PDF/HTML 硬换行的续接（如句中 "Inc." 后换行小写续接）。
     */
    private static boolean lineEndsSentence(String prevLine, String nextLine) {
        String t = prevLine.trim();
        if (t.isEmpty()) {
            return false;
        }
        char last = t.charAt(t.length() - 1);
        if (last == '。' || last == '！' || last == '？' || last == '!' || last == '?') {
            return true;
        }
        if (last == '.') {
            String n = nextLine.trim();
            if (n.isEmpty()) {
                return true;
            }
            char first = n.charAt(0);
            return Character.isUpperCase(first) || first == '"' || first == '“'
                    || (first >= 0x4E00 && first <= 0x9FFF);
        }
        return false;
    }

    /**
     * 超长兜底：片段超过 {@link #MAX_SNIPPET_CHARS} 时，以关键字为中心取窗口并在句子/子句
     * 边界处切断（首尾加省略号），尽量保留期间状语与金额；正常长度原样返回。绝大多数片段为
     * 单句（200-500 字），不会触发。
     */
    private static String capSnippet(String snippet, Pattern kwRegex) {
        if (snippet.length() <= MAX_SNIPPET_CHARS) {
            return snippet;
        }
        Matcher m = kwRegex.matcher(snippet);
        int kw = m.find() ? m.start() : snippet.length() / 2;
        // 窗口起点：关键字之前约 1/3 容量处，再回退到最近的句子/子句边界之后，保证尽量从句首开始
        int start = boundaryAfter(snippet, Math.max(0, kw - MAX_SNIPPET_CHARS / 3));
        int end = Math.min(snippet.length(), start + MAX_SNIPPET_CHARS);
        int cutEnd = boundaryBefore(snippet, end);
        if (cutEnd <= start) {
            cutEnd = end;
        }
        StringBuilder sb = new StringBuilder();
        if (start > 0) {
            sb.append("…");
        }
        sb.append(snippet, start, Math.min(snippet.length(), cutEnd));
        if (cutEnd < snippet.length()) {
            sb.append("…");
        }
        return sb.toString();
    }

    /** 返回 pos 之前最后一个句子/子句边界的切分点（分隔符之后的位置），找不到返回 0。 */
    private static int boundaryAfter(String s, int pos) {
        Matcher m = BOUNDARY_RE.matcher(s);
        int best = 0;
        while (m.find() && m.start() < pos) {
            best = m.end();
        }
        return best;
    }

    /** 返回 pos 之前最后一个句子/子句边界的切分点（分隔符之后的位置），找不到返回 pos。 */
    private static int boundaryBefore(String s, int pos) {
        Matcher m = BOUNDARY_RE.matcher(s);
        int best = -1;
        while (m.find() && m.end() <= pos) {
            best = m.end();
        }
        return best < 0 ? pos : best;
    }

    /**
     * 相关性打分：单遍扫描关键字正则——命中不同关键字短语数（权重 3）+ 命中次数（上限 5）
     * + 含金额 3 分 + 含单位 1 分。
     */
    private int scoreSnippet(String snippet, Pattern kwRegex) {
        Set<String> distinct = new HashSet<>();
        int occurrences = 0;
        Matcher m = kwRegex.matcher(snippet);
        while (m.find()) {
            occurrences++;
            distinct.add(m.group().toLowerCase(Locale.ROOT));
        }
        if (distinct.isEmpty()) {
            return 0;
        }
        int score = distinct.size() * 3 + Math.min(occurrences, 5);
        if (AMOUNT_RE.matcher(snippet).find()) {
            score += 3;
        }
        if (snippet.matches(".*(千|百萬|百万|萬|万|million|thousand|billion).*")) {
            score += 1;
        }
        return score;
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
        sb.append("请仅依据下列财报原文片段，提取公司 ").append(ticker).append(" 在目标期间 ").append(period)
                .append(" ").append(periodScope(period)).append("的指标 ").append(def.getCode())
                .append("（").append(name).append("）的数值。\n");
        sb.append("注意：财报片段可能以不同口径披露该指标——单季度（three months ended）、"
                + "年初至今累计（six/nine months ended、年内累计，如半年报/三季报披露的回购金额）、"
                + "或完整财年（year/twelve months ended、全年，如年报披露的全年回购金额）。"
                + "请按片段实际披露的口径如实提取并用 periodType 标注；片段只披露累计/全年值时，"
                + "不要把它当成单季值，也不要自行把累计值差分或推算为单季值。\n");
        sb.append("检索关键字：").append(String.join("、", keywords)).append("\n");
        if (def.getHint() != null && !def.getHint().isBlank()) {
            sb.append("提示：").append(def.getHint()).append("\n");
        }
        sb.append("# 原文片段（按相关性排序，均来自该公司财报，可能含当期数与上年同期对比数）：\n");
        for (int i = 0; i < snippets.size(); i++) {
            sb.append(i + 1).append(". 【").append(snippets.get(i).file().getFileName())
                    .append(" 第").append(snippets.get(i).line()).append("行】")
                    .append(snippets.get(i).text()).append("\n");
        }
        sb.append("""
                # 严格要求：
                1. 只能使用片段中明确披露的数据，不得推测或计算（片段没有就 found=false）；
                2. 注意区分当期数与上年同期/前期对比数（如 "compared to ... in the same period of 2024"、
                   表格中 2024 列均为对比数），只提取目标期间的当期数；对比数一律不要；
                3. periodType 按数值实际覆盖的期间填写：
                   - SINGLE_Q：单个季度（三个月）的发生额，且就是目标期间所在季度；
                   - CUMULATIVE：年初至期末的累计发生额（半年/九个月等，非完整财年）；
                   - FY：完整财年（全年/十二个月）的发生额；
                   片段只披露哪种口径，values 中就只放哪一项；单季与累计/全年都有明确披露时可放多项；
                   不得把累计值标成 SINGLE_Q，也不得为凑齐口径而推算；
                4. 金额一律换算为"百万"单位（原文为千元/千港元/千人民币则除以1000，为十亿美元则乘以1000；原文已是百万/百萬则不变）；
                5. 只输出一个 JSON 对象，不要输出任何解释文字，格式：
                { "METRIC_CODE": { "found": true, "values": [
                    {"value": 123.45, "unit": "million", "periodType": "SINGLE_Q", "confidence": 90, "evidence": "原文短句"},
                    {"value": 678.90, "unit": "million", "periodType": "FY", "confidence": 88, "evidence": "原文短句"}
                ] } }
                其中 METRIC_CODE 为占位符，需要替换成具体的指标名；只披露一种口径时 values 中只有一项；
                confidence 为 0-100 的整数，表达你对数值与口径的把握。
                """);
        return sb.toString();
    }

    /** 目标期间口径描述：FY 财年全年；H 中期半年累计；Q 季度（片段可能披露单季或累计/全年值，按实际口径标注）。 */
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
        return "季度（季度报告；片段披露单季值填 SINGLE_Q，仅披露年初至今累计值填 CUMULATIVE，"
                + "仅披露全年值填 FY）";
    }

    /** 根据期间标签推断默认存储口径：FY→FY，H1/H2→CUMULATIVE，Qn→SINGLE_Q。 */
    private static PeriodType periodTypeOf(String period) {
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

    /** 存储键：数值实际归属的规范期间 + 口径。 */
    public record StorageKey(String fiscalPeriod, PeriodType periodType) {}

    /**
     * 把"目标期间 + 数值实际口径"映射为存储键。财报片段可能只披露累计/全年值（如港股 00700
     * 半年报的回购为 H1 累计、年报兜底四季度时回购为全年值），数值按其实际披露口径入库，
     * 而非套用目标期间标签：
     * <ul>
     *   <li>FY：目标为 FYnnnn 时原样；目标为季度时仅财年末季（年报兜底场景）会出现，
     *       FY 结束自然年即该季所在自然年（fye=12：2025Q4→FY2025；fye=3：2026Q1→FY2026）；</li>
     *   <li>CUMULATIVE：季度目标存 (yQq, CUMULATIVE)，与三大表累计行键控一致（H1→Q2、9M→Q3；
     *       Q1 累计即 Q1 单季）；累计到财年末季即完整财年，按 FY 存；FY 目标按 FY 存；</li>
     *   <li>SINGLE_Q：季度目标原样；FY 目标存该财年末季自然季度标签（fye=12：FY2025→2025Q4；
     *       fye=3：FY2026→2026Q1）。</li>
     * </ul>
     * 目标期间无法解析时返回 null。
     */
    static StorageKey resolveStorageKey(String requestedPeriod, PeriodType scope, int fyeMonth) {
        FiscalPeriod req = FiscalPeriod.parse(requestedPeriod);
        if (req == null) {
            return null;
        }
        int fye = (fyeMonth >= 1 && fyeMonth <= 12) ? fyeMonth : 12;
        boolean reqIsFy = req.ordinal() == 5;
        int fySlotQ = (fye - 1) / 3 + 1;   // 财年末季在自然年中的季度槽位（fye=12→Q4，fye=3→Q1）
        switch (scope) {
            case FY:
                return new StorageKey(reqIsFy ? req.canonical() : "FY" + req.year(), PeriodType.FY);
            case CUMULATIVE:
                if (reqIsFy) {
                    return new StorageKey(req.canonical(), PeriodType.FY);
                }
                if (req.ordinal() == fySlotQ) {
                    // 年初至今累计到财年末季 = 完整财年（如 fye=12 时 2025Q4 的累计值即 FY2025 全年）
                    return new StorageKey("FY" + req.year(), PeriodType.FY);
                }
                return new StorageKey(req.canonical(), PeriodType.CUMULATIVE);
            case SINGLE_Q:
            default:
                if (reqIsFy) {
                    return new StorageKey(req.year() + "Q" + fySlotQ, PeriodType.SINGLE_Q);
                }
                return new StorageKey(req.canonical(), PeriodType.SINGLE_Q);
        }
    }

    /**
     * 目标期间经口径映射后可能落入的存储期间：目标期间本身 + 财年末季/全年别名
     *（季度目标在财年末季时可能写入 FY 全年值；FY 目标可能写入财年末季单季值），
     * 用于"已提取跳过"判断覆盖实际写入的行。目标期间无法解析时仅返回其本身。
     */
    static List<String> candidateStoragePeriods(String period, int fyeMonth) {
        FiscalPeriod fp = FiscalPeriod.parse(period);
        if (fp == null) {
            return List.of(period);
        }
        int fye = (fyeMonth >= 1 && fyeMonth <= 12) ? fyeMonth : 12;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(period);
        if (fp.ordinal() == 5) {
            out.add(fp.year() + "Q" + ((fye - 1) / 3 + 1));
        } else if (fp.ordinal() == (fye - 1) / 3 + 1) {
            out.add("FY" + fp.year());
        }
        return new ArrayList<>(out);
    }

    /** 解析模型报告的数值口径（SINGLE_Q/CUMULATIVE/FY，容错 YEAR/YTD 等写法）；无法识别时按目标期间推断。 */
    private static PeriodType parseReportedScope(String raw, String requestedPeriod) {
        if (raw != null) {
            String s = raw.trim().toUpperCase(Locale.ROOT);
            if (s.contains("CUMUL") || s.contains("YTD")) {
                return PeriodType.CUMULATIVE;
            }
            if (s.contains("FY") || s.contains("YEAR") || s.contains("ANNUAL")) {
                return PeriodType.FY;
            }
            if (s.contains("SINGLE") || s.contains("QUARTER")) {
                return PeriodType.SINGLE_Q;
            }
        }
        return periodTypeOf(requestedPeriod);
    }

    /**
     * 解析模型 JSON 回答，按置信度阈值产出 KEYWORD 来源指标行；未找到/置信度不足/解析失败返回空列表。
     * 模型按片段实际披露口径返回 values（每项 SINGLE_Q/CUMULATIVE/FY），这里映射为实际存储键——
     * 如目标 2025Q4 但片段来自年报、只披露全年值，则入库 (FY2025, FY)，而非误标 (2025Q4, SINGLE_Q)。
     * 兼容旧版扁平形状（{value, unit, periodType, confidence} 单值）。
     */
    private List<MetricValueDO> parseResponse(String ticker, String period, KeywordMetricDef def,
                                              String text, Path primaryFile, int fyeMonth) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return List.of();
        }
        JSONObject obj;
        try {
            obj = JSON.parseObject(text.substring(start, end + 1));
        } catch (Exception e) {
            log.debug("Keyword extract JSON 解析失败: {}", e.getMessage());
            return List.of();
        }

        JSONObject cell = obj.getJSONObject(def.getCode());
        if (cell == null) {
            // 兼容模型把 code 小写输出
            cell = obj.getJSONObject(def.getCode().toLowerCase(Locale.ROOT));
        }
        if (cell == null || !cell.getBooleanValue("found", false)) {
            return List.of();
        }

        // 新约定 values 数组（每项一个口径）；缺失时回退旧版扁平对象（单值）
        List<JSONObject> findings = new ArrayList<>();
        JSONArray values = cell.getJSONArray("values");
        if (values != null && !values.isEmpty()) {
            for (int i = 0; i < values.size(); i++) {
                JSONObject item = values.getJSONObject(i);
                if (item != null) {
                    findings.add(item);
                }
            }
        } else {
            findings.add(cell);
        }

        String documentId = primaryFile == null ? null : primaryFile.getFileName().toString();
        // 同一存储键去重：保留置信度高者
        Map<String, MetricValueDO> byKey = new LinkedHashMap<>();
        for (JSONObject item : findings) {
            BigDecimal value = item.getBigDecimal("value");
            if (value == null) {
                continue;
            }
            Integer confidence = item.getInteger("confidence");
            if (confidence != null && confidence < def.getMinConfidence()) {
                log.debug("Keyword extract 置信度不足: {} {} {} = {} ({})",
                        ticker, period, def.getCode(), value, confidence);
                continue;
            }
            StorageKey key = resolveStorageKey(period,
                    parseReportedScope(item.getString("periodType"), period), fyeMonth);
            if (key == null) {
                continue;
            }
            MetricValueDO row = MetricValueDO.builder()
                    .ticker(ticker)
                    .fiscalPeriod(key.fiscalPeriod())
                    .periodType(key.periodType().name())
                    .metricCode(def.getCode())
                    .value(normalizeUnit(value, item.getString("unit")))
                    .unit("million")
                    .source(MetricSource.KEYWORD.name())
                    .confidence(confidence)
                    .documentId(documentId)
                    .build();
            String mapKey = key.fiscalPeriod() + "|" + key.periodType();
            MetricValueDO old = byKey.get(mapKey);
            if (old == null || (confidence != null
                    && (old.getConfidence() == null || confidence > old.getConfidence()))) {
                byKey.put(mapKey, row);
            }
        }
        return new ArrayList<>(byKey.values());
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
