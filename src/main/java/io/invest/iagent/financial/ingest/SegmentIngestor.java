package io.invest.iagent.financial.ingest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.financial.config.prop.FinancialProperties;
import io.invest.iagent.financial.model.CompanyDO;
import io.invest.iagent.financial.model.MetricSource;
import io.invest.iagent.financial.model.SegmentDO;
import io.invest.iagent.financial.model.SegmentValueDO;
import io.invest.iagent.financial.repository.FinancialRepository;
import io.invest.iagent.rag.filing.model.FiscalPeriod;
import io.invest.iagent.utils.ProcessRunner;
import io.invest.iagent.utils.PythonResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分部数据采集器：调用 segment-financial-report skill 的 extract_segments.py（本地财报文件解析，
 * 不联网），将扁平 JSON（segment × metric × period）入库 fin_segment / fin_segment_value。
 *
 * <p>财报文件来源：{@code FinancialReportService#download} 下载到
 * workspace/financial_reports/&lt;市场&gt;/&lt;ticker&gt;/ 的 PDF/HTML 产物（与下载器
 * localPath 布局一致），由本采集器扫描后通过 {@code --files} 显式传给提取引擎；
 * 引擎按公司配置（skill 的 config/extraction/&lt;TICKER&gt;.json）解析，
 * 未下载财报或无公司配置时脚本退出码 2，本采集器返回提示。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class SegmentIngestor {

    private static final String SCRIPT_REL = "skills/segment-financial-report/scripts/extract_segments.py";
    /** 引擎期间标签 2025FY / 2025Q1 / 2025H1 */
    private static final Pattern PERIOD_RE = Pattern.compile("(\\d{4})(FY|Q[1-4]|H[12])");
    /**
     * 下载产物文件名：{@code <ticker>_<yyyy-MM-dd|yyyyMMdd>_<ANNUAL|INTERIM|QUARTERLY|10-K|10-Q|6-K|8-K|20-F>.<ext>}。
     * 与 Python {@code FilingContext._REPORT_FILE_PATTERN} 保持一致。
     * 8-K 为美股本土公司季度业绩新闻稿（财年末季单季分部数据的唯一来源），按季报处理。
     */
    private static final Pattern REPORT_FILE_RE = Pattern.compile(
            "^[^_]+_(?<date>\\d{4}-\\d{2}-\\d{2}|\\d{8})_"
                    + "(?<type>ANNUAL|INTERIM|QUARTERLY|10-K|10-Q|6-K|8-K|20-F)\\.(pdf|html?)$",
            Pattern.CASE_INSENSITIVE);
    /** 引擎指标编码 → 标准指标编码（其余编码与 catalog 一致） */
    private static final Map<String, String> METRIC_CODE_MAP = Map.of(
            "RD_EXPENSES", "RD_EXPENSE");

    @Autowired
    private Path workspace;

    @Autowired
    private FinancialProperties properties;

    @Autowired
    private FinancialRepository repository;

    /**
     * 采集结果。
     */
    public record SegmentResult(boolean extracted, int segments, int values, List<String> warnings) {}

    /** 兼容入口：不传覆盖期间，解析全部已下载财报文件。 */
    public SegmentResult ingest(String ticker) {
        return ingest(ticker, null);
    }

    /**
     * 执行分部数据提取与入库。
     *
     * @param ticker            裸 ticker（BABA / 00700，不带市场前缀）
     * @param coveredPeriodList 本次三大表采集覆盖的规范期间（自然年口径，如 2025Q1 / FY2025）；
     *                          非空时仅解析期间落在该集合内的财报文件（含其上年同期对比表），
     *                          null/空表示解析全部已下载财报文件
     */
    public SegmentResult ingest(String ticker, List<String> coveredPeriodList) {
        List<String> warnings = new ArrayList<>();
        // 财年结束月（三大表采集时写入 fin_company）：文件名期间标签 → 自然年口径，与引擎输出口径一致
        int fyeMonth = resolveFyeMonth(ticker);
        // 财报文件来自 FinancialReportService 下载产物：workspace/financial_reports/<市场>/<ticker>/
        Path reportBaseDir = Path.of(properties.getReportBaseDir()).toAbsolutePath();
        List<Path> reportFiles = discoverReportFiles(reportBaseDir, ticker);
        if (coveredPeriodList != null && !coveredPeriodList.isEmpty()) {
            int before = reportFiles.size();
            reportFiles = filterReportsByPeriods(reportFiles, new HashSet<>(coveredPeriodList), fyeMonth);
            log.info("Segment report filter: ticker={}, coveredPeriods={}, report files {} -> {}",
                    ticker, coveredPeriodList.size(), before, reportFiles.size());
        }
        if (reportFiles.isEmpty()) {
            warnings.add("未找到已下载的财报文件（" + reportBaseDir + " 下无 " + ticker
                    + " 的 PDF/HTML 财报"
                    + (coveredPeriodList != null && !coveredPeriodList.isEmpty() ? "落在指定期间内" : "")
                    + "），请先下载财报（FinancialReportService#download）。");
            log.info("Segment ingest skipped for {}: no report files under {}", ticker, reportBaseDir);
            return new SegmentResult(false, 0, 0, warnings);
        }

        Path output = workspace.resolve("temp").resolve(ticker + "_segments.json");
        Path script = workspace.resolve(SCRIPT_REL);
        List<String> cmd = new ArrayList<>(List.of(
                PythonResolver.resolve(properties.getPythonExecutable()), script.toAbsolutePath().toString(),
                "--ticker", ticker,
                "--workspace", workspace.toAbsolutePath().toString(),
                "--output", output.toAbsolutePath().toString(),
                "--files"));
        reportFiles.forEach(f -> cmd.add(f.toAbsolutePath().toString()));
        ProcessRunner.Result result;
        try {
            result = ProcessRunner.run(cmd, null, properties.getSegmentTimeoutSeconds());
        } catch (Exception e) {
            warnings.add("分部数据提取执行失败: " + e.getMessage());
            return new SegmentResult(false, 0, 0, warnings);
        }

        // 退出码 2：文件未解析出数据 / 无公司配置（脚本 stderr 已含中文提示）
        if (result.getExitCode() == 2 || !Files.isRegularFile(output)) {
            String hint = lastLines(result.getStderr(), 400);
            warnings.add("未生成分部数据（可能财报格式暂不支持或暂无该公司分部配置）。" + hint);
            log.info("Segment ingest skipped for {}: rc={}", ticker, result.getExitCode());
            return new SegmentResult(false, 0, 0, warnings);
        }
        if (!result.isSuccess()) {
            warnings.add("分部数据提取脚本失败(rc=" + result.getExitCode() + "): "
                    + lastLines(result.getStderr(), 400));
            return new SegmentResult(false, 0, 0, warnings);
        }

        try {
            JSONArray records = JSON.parseArray(Files.readString(output, StandardCharsets.UTF_8));
            // 引擎按公司财年结束月输出财年口径标签（如 BABA 截至 2026-06 的季度为 2027Q1），
            // 用开头解析的财年结束月，统一转换为自然年标签（2026Q2）
            // segmentCode -> 分部定义（LinkedHashMap 保留首次出现顺序 = 引擎树序）。
            // 分部支持多层结构（如 BABA：TAOBAO_TMALL → CHINA_COMMERCE_RETAIL → CUSTOMER_MANAGEMENT），
            // 同一分部跨多条记录出现，这里归并定义：名称补缺、父编码优先取非空——
            // 引擎跨文件合并时，某文件未识别出父分部会先输出"无父"记录，不能让首条记录压平层级。
            Map<String, SegmentDO> segmentMap = new LinkedHashMap<>();
            Map<String, Integer> firstSeen = new HashMap<>();
            // 指标值按 (期间|分部|指标) 去重：引擎层级合并的边界情况下可能输出重复记录
            Map<String, SegmentValueDO> valueMap = new LinkedHashMap<>();
            int seq = 0;

            for (int i = 0; i < records.size(); i++) {
                JSONObject rec = records.getJSONObject(i);
                if (rec.getBooleanValue("__meta__", false)) {
                    continue;
                }
                String segCode = rec.getString("segmentCode");
                String period = canonicalPeriod(rec.getString("period"), fyeMonth);
                String metricCode = METRIC_CODE_MAP.getOrDefault(rec.getString("metricCode"), rec.getString("metricCode"));
                if (segCode == null || period == null || metricCode == null) {
                    continue;
                }
                String parentCode = blankToNull(rec.getString("parentSegmentCode"));
                SegmentDO seg = segmentMap.get(segCode);
                if (seg == null) {
                    seg = SegmentDO.builder()
                            .ticker(ticker)
                            .segmentCode(segCode)
                            .segmentName(blankToNull(rec.getString("segmentName")))
                            .parentCode(parentCode)
                            .level(rec.getIntValue("level", 1))
                            .build();
                    segmentMap.put(segCode, seg);
                    firstSeen.put(segCode, seq++);
                } else if (parentCode != null) {
                    // 父编码以"有父"记录为准；名称缺失时补上
                    if (seg.getSegmentName() == null) {
                        seg.setSegmentName(blankToNull(rec.getString("segmentName")));
                    }
                    if (seg.getParentCode() == null) {
                        seg.setParentCode(parentCode);
                        seg.setLevel(rec.getIntValue("level", seg.getLevel()));
                    }
                }

                valueMap.computeIfAbsent(period + "|" + segCode + "|" + metricCode,
                        k -> SegmentValueDO.builder()
                                .ticker(ticker)
                                .fiscalPeriod(period)
                                .segmentCode(segCode)
                                .metricCode(metricCode)
                                .value(rec.getBigDecimal("value"))
                                .currency(rec.getString("currency"))
                                .unit("million")
                                .source(MetricSource.SEGMENT_PARSE.name())
                                .confidence(rec.getInteger("confidenceScore"))
                                .build());
            }

            if (valueMap.isEmpty()) {
                warnings.add("分部脚本未解析出有效数据。");
                return new SegmentResult(false, 0, 0, warnings);
            }

            // 构建多层分部树：补全缺失的父分部、断裂循环引用，按树深归一 level、
            // 按先根遍历赋值 sortOrder（保证渲染时父分部行紧邻其子树）
            List<SegmentDO> segments = resolveHierarchy(segmentMap, firstSeen, warnings);
            List<SegmentValueDO> values = new ArrayList<>(valueMap.values());

            fillYoY(values);
            // 全量刷新分部数据：先清空旧数据，避免期间口径调整后新旧标签并存
            repository.deleteSegmentsByTicker(ticker);
            repository.batchUpsertSegments(segments);
            repository.batchUpsertSegmentValues(values);
            repository.recordBatch(ticker, "SEGMENT_PARSE", "SUCCESS",
                    values.stream().map(SegmentValueDO::getFiscalPeriod).distinct().sorted()
                            .reduce((a, b) -> a + "," + b).orElse(""),
                    "segments=" + segments.size() + ", values=" + values.size());

            log.info("Segment ingest done: ticker={}, segments={}, values={}",
                    ticker, segments.size(), values.size());
            return new SegmentResult(true, segments.size(), values.size(), warnings);
        } catch (Exception e) {
            log.error("分部数据解析失败: ticker={}", ticker, e);
            warnings.add("分部数据解析失败: " + e.getMessage());
            return new SegmentResult(false, 0, 0, warnings);
        }
    }

    /**
     * 发现 FinancialReportService 下载的财报文件：
     * 布局 {@code <reportBaseDir>/<市场 US|HK|CN>/<ticker>/<ticker>_<日期>_<类型>.<ext>}
     * （市场目录与下载器 localPath 前缀一致），取 pdf/htm/html 正文文件，按文件名排序（日期升序，
     * 与引擎"先处理的记录优先"的去重约定一致）。
     */
    static List<Path> discoverReportFiles(Path reportBaseDir, String ticker) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(reportBaseDir)) {
            return files;
        }
        try (var marketDirs = Files.list(reportBaseDir)) {
            for (Path marketDir : marketDirs.filter(Files::isDirectory).sorted().toList()) {
                Path tickerDir = marketDir.resolve(ticker);
                if (!Files.isDirectory(tickerDir)) {
                    continue;
                }
                try (var entries = Files.list(tickerDir)) {
                    entries.filter(Files::isRegularFile)
                            .filter(p -> {
                                String n = p.getFileName().toString().toLowerCase();
                                return n.endsWith(".pdf") || n.endsWith(".htm") || n.endsWith(".html");
                            })
                            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                            .forEach(files::add);
                }
            }
        } catch (IOException e) {
            log.warn("扫描财报目录失败: {}, {}", reportBaseDir, e.getMessage());
        }
        return files;
    }

    /**
     * 按覆盖期间过滤财报文件：仅保留自然年期间落在 coveredCanonical 内的财报。
     * 每份财报同时披露当期与上年同期对比表：当期命中即保留；当期未命中、但其去年同期
     * 命中覆盖期间时，仅在<strong>该去年同期没有"当期即命中"的财报</strong>时才靠本期
     * 对比列补数（避免当期财报已存在时仍冗余拉入次年财报，如 BABA 2024Q1 已有
     * 20240514 财报时，不应再因对比列拉入 20250515）。
     *
     * <p>年报兜底：财年末季单季数据只随年报披露（如 fyeMonth=12 的公司不发 Q4 季报，
     * 四季度数据在 FY 年报中；fyeMonth=3 时对应自然年 Q1）。年报当期（FY）未命中、
     * 但其财年末季（自然年口径 {@code <FY年份>Q<(fyeMonth-1)/3+1>}）命中覆盖期间，
     * 且该季度没有专属当期财报时，才用年报补数——优先取季报，季报不存在时取年报。
     * 文件名无法解析期间时保守保留（避免误删）。
     */
    static List<Path> filterReportsByPeriods(List<Path> files, Set<String> coveredCanonical, int fyeMonth) {
        // 先汇总每份财报"当期"所属自然年期间，用于判断某覆盖期间是否已有专属当期财报
        Map<Path, String> periodOf = new LinkedHashMap<>();
        Set<String> ownPeriods = new HashSet<>();
        for (Path f : files) {
            String p = reportNaturalPeriod(f.getFileName().toString(), fyeMonth);
            periodOf.put(f, p);
            if (p != null) {
                ownPeriods.add(p);
            }
        }

        List<Path> kept = new ArrayList<>();
        for (Path f : files) {
            String period = periodOf.get(f);
            if (period == null) {
                kept.add(f);
                continue;
            }
            if (coveredCanonical.contains(period)) {
                kept.add(f);
                continue;
            }
            // 财报含上年同期对比表：去年同期命中、且该同期没有专属当期财报时，才靠对比列补数
            FiscalPeriod fp = FiscalPeriod.parse(period);
            if (fp != null) {
                String yearAgo = fp.yearAgo().canonical();
                if (coveredCanonical.contains(yearAgo) && !ownPeriods.contains(yearAgo)) {
                    kept.add(f);
                    continue;
                }
            }
            // 年报兜底：财年末季（fyeMonth=12 → 自然年 Q4，fyeMonth=3 → Q1，以此类推）
            // 只随年报披露，该季命中覆盖期间且无专属季报时，用年报补数（优先季报、缺失才取年报）
            if (period.startsWith("FY") && fyeMonth >= 1 && fyeMonth <= 12) {
                String lastQuarter = period.substring(2) + "Q" + ((fyeMonth - 1) / 3 + 1);
                if (coveredCanonical.contains(lastQuarter) && !ownPeriods.contains(lastQuarter)) {
                    kept.add(f);
                }
            }
        }
        return kept;
    }

    /**
     * 解析下载文件名对应的<strong>自然年</strong>规范期间（与三大表 futu 口径一致，如 2025Q3 / FY2025）。
     * 报告通常在期末后 1–2 个月发布，按发布月反推其覆盖的自然季度；年报按财年结束月定自然年
     *（财年结束月 ≥6 如 12 月：年报次年发布，自然年为发布年−1；≤5 如 3 月：同年发布，自然年为发布年）。
     * 无法解析返回 null。
     */
    static String reportNaturalPeriod(String fileName, int fyeMonth) {
        Matcher m = REPORT_FILE_RE.matcher(fileName == null ? "" : fileName.trim());
        if (!m.find()) {
            return null;
        }
        String date = m.group("date");
        int year = Integer.parseInt(date.substring(0, 4));
        int month = date.contains("-") ? Integer.parseInt(date.substring(5, 7))
                : Integer.parseInt(date.substring(4, 6));
        String type = m.group("type").toUpperCase(Locale.ROOT);
        if (type.equals("ANNUAL") || type.equals("10-K") || type.equals("20-F")) {
            int endYear = fyeMonth >= 6 ? year - 1 : year;
            return "FY" + endYear;
        }
        // 中报/季报：按发布月反推最近结束的自然季度（中报 H1 期末 6 月，多在 8–9 月发布，落在 Q2 桶）
        if (month <= 3) {
            return (year - 1) + "Q4";
        } else if (month <= 6) {
            return year + "Q1";
        } else if (month <= 9) {
            return year + "Q2";
        }
        return year + "Q3";
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

    /** 兼容入口：按自然年（财年结束月 12）规范化引擎标签。 */
    public static String canonicalPeriod(String raw) {
        return canonicalPeriod(raw, 12);
    }

    /**
     * 引擎期间标签（财年口径）→ 规范期间（自然年口径）。
     * <p>引擎按公司 fiscalYearEndMonth 输出财年标签（如 BABA 财年截至 3 月：截至 2026-06 的季度为 2027Q1），
     * 这里按财年结束月反算该期结束的自然月，统一为自然年标签（2026Q2）；
     * 年报 FY 标签的年份即结束自然年（BABA FY2026 截至 2026-03），保持不变。
     * 无法识别返回 null。
     */
    public static String canonicalPeriod(String raw, int fyeMonth) {
        if (raw == null) {
            return null;
        }
        Matcher m = PERIOD_RE.matcher(raw.trim().toUpperCase());
        if (!m.find()) {
            return null;
        }
        int fy = Integer.parseInt(m.group(1));
        String tag = m.group(2);
        if ("FY".equals(tag)) {
            return "FY" + fy;
        }
        int fye = (fyeMonth >= 1 && fyeMonth <= 12) ? fyeMonth : 12;
        int span = tag.startsWith("Q") ? 3 * Integer.parseInt(tag.substring(1))   // 财年第 n 季：3n 个月
                : 6 * Integer.parseInt(tag.substring(1));                        // 财年第 n 个半年：6n 个月
        int endMonth = fye + span;
        int endYear = fy;
        if (endMonth > 12) {
            endMonth -= 12;
        } else {
            endYear = fy - 1; // 结束月未跨年：该期落在财年起始的自然年
        }
        if (tag.startsWith("Q")) {
            return endYear + "Q" + ((endMonth - 1) / 3 + 1);
        }
        return endYear + "H" + (endMonth <= 6 ? 1 : 2);
    }

    /**
     * 归一化多层分部树：
     * <ol>
     *   <li>父分部在记录中缺失（父本身无指标值，纯分组标题行）时补占位节点，
     *       名称暂用编码——后续采集到父分部真实行会经 upsert 自动更新名称；</li>
     *   <li>断裂父链循环/自引用（异常数据兜底，避免死循环）；</li>
     *   <li>level 按树深归一（根=1），sortOrder 按先根遍历赋值（同父下按首次出现顺序），
     *       保证 fin_segment 按 sort_order 查询时父分部行紧邻其子树。</li>
     * </ol>
     *
     * @param segmentMap 分部定义（会被就地补全/修正）
     * @param firstSeen  各分部首次出现序号（兄弟排序用）
     * @param warnings   收集层级修复提示
     * @return 先根遍历有序的分部列表
     */
    static List<SegmentDO> resolveHierarchy(Map<String, SegmentDO> segmentMap,
                                            Map<String, Integer> firstSeen,
                                            List<String> warnings) {
        // 1. 补全缺失的父分部（可能整条父链都缺失，循环至无新增）
        boolean added = true;
        while (added) {
            added = false;
            for (SegmentDO seg : new ArrayList<>(segmentMap.values())) {
                String parent = seg.getParentCode();
                if (parent != null && !segmentMap.containsKey(parent)) {
                    segmentMap.put(parent, SegmentDO.builder()
                            .ticker(seg.getTicker())
                            .segmentCode(parent)
                            .segmentName(parent)
                            .level(Math.max(1, seg.getLevel() - 1))
                            .build());
                    firstSeen.put(parent, firstSeen.getOrDefault(seg.getSegmentCode(), 0));
                    warnings.add("父分部 " + parent + " 无数据行，已补占位节点（后续采集到数据会自动补全名称）");
                    added = true;
                }
            }
        }

        // 2. 断裂循环引用 / 自引用：沿父链检测，成环时断开成环节点的父边
        for (SegmentDO seg : segmentMap.values()) {
            Set<String> chain = new HashSet<>();
            String code = seg.getSegmentCode();
            while (code != null && chain.add(code)) {
                SegmentDO node = segmentMap.get(code);
                code = node == null ? null : node.getParentCode();
            }
            if (code != null) {
                SegmentDO node = segmentMap.get(code);
                if (node != null && node.getParentCode() != null) {
                    warnings.add("分部父链存在循环引用（" + code + " → " + node.getParentCode()
                            + "），已断开为一级分部");
                    node.setParentCode(null);
                }
            }
        }

        // 3. 按父链接组织树；根节点与兄弟节点均按首次出现顺序排序
        Map<String, List<String>> childrenOf = new HashMap<>();
        List<String> roots = new ArrayList<>();
        for (SegmentDO seg : segmentMap.values()) {
            if (seg.getParentCode() == null) {
                roots.add(seg.getSegmentCode());
            } else {
                childrenOf.computeIfAbsent(seg.getParentCode(), k -> new ArrayList<>())
                        .add(seg.getSegmentCode());
            }
        }
        Comparator<String> bySeen = Comparator.comparingInt(c -> firstSeen.getOrDefault(c, Integer.MAX_VALUE));
        roots.sort(bySeen);
        childrenOf.values().forEach(list -> list.sort(bySeen));

        // 4. 先根遍历：level=树深，sortOrder 递增
        List<SegmentDO> ordered = new ArrayList<>(segmentMap.size());
        int[] counter = {0};
        for (String root : roots) {
            walkHierarchy(root, 1, segmentMap, childrenOf, ordered, counter);
        }
        return ordered;
    }

    /** 先根遍历赋值 level/sortOrder 并收集有序分部。 */
    private static void walkHierarchy(String code, int depth, Map<String, SegmentDO> segmentMap,
                                      Map<String, List<String>> childrenOf,
                                      List<SegmentDO> ordered, int[] counter) {
        SegmentDO seg = segmentMap.get(code);
        if (seg == null) {
            return;
        }
        seg.setLevel(depth);
        seg.setSortOrder(counter[0]++);
        ordered.add(seg);
        for (String child : childrenOf.getOrDefault(code, List.of())) {
            walkHierarchy(child, depth + 1, segmentMap, childrenOf, ordered, counter);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * 按 (分部, 指标) 分组计算同比：FY 对比上一 FY，Qn 对比去年同 Q，Hn 同理。
     */
    private void fillYoY(List<SegmentValueDO> values) {
        // key: segmentCode|metricCode -> (periodKey -> row)
        Map<String, Map<String, SegmentValueDO>> byKey = new HashMap<>();
        for (SegmentValueDO v : values) {
            if (v.getValue() == null) {
                continue;
            }
            byKey.computeIfAbsent(v.getSegmentCode() + "|" + v.getMetricCode(), k -> new TreeMap<>())
                    .put(v.getFiscalPeriod(), v);
        }
        for (Map<String, SegmentValueDO> periodMap : byKey.values()) {
            for (SegmentValueDO v : periodMap.values()) {
                String year;
                String suffix;
                if (v.getFiscalPeriod().startsWith("FY")) {
                    year = v.getFiscalPeriod().substring(2);
                    suffix = "FY";
                } else if (v.getFiscalPeriod().length() >= 5) {
                    year = v.getFiscalPeriod().substring(0, 4);
                    suffix = v.getFiscalPeriod().substring(4);
                } else {
                    continue;
                }
                String priorLabel = "FY".equals(suffix)
                        ? "FY" + (Integer.parseInt(year) - 1)
                        : (Integer.parseInt(year) - 1) + suffix;
                SegmentValueDO prior = periodMap.get(priorLabel);
                if (prior != null && prior.getValue() != null
                        && prior.getValue().compareTo(BigDecimal.ZERO) != 0) {
                    v.setYoy(v.getValue().subtract(prior.getValue())
                            .multiply(BigDecimal.valueOf(100))
                            .divide(prior.getValue().abs(), 2, RoundingMode.HALF_UP));
                }
            }
        }
    }

    private static String lastLines(String s, int max) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(trimmed.length() - max);
    }
}
