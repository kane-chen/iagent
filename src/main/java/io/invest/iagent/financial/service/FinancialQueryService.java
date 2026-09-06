package io.invest.iagent.financial.service;

import io.invest.iagent.financial.config.FinancialProperties;
import io.invest.iagent.financial.ingest.FutuCodeUtil;
import io.invest.iagent.financial.ingest.SegmentIngestor;
import io.invest.iagent.financial.model.CompanyDO;
import io.invest.iagent.financial.model.MetricCatalog;
import io.invest.iagent.financial.model.MetricDef;
import io.invest.iagent.financial.model.MetricSource;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.model.PeriodType;
import io.invest.iagent.financial.model.SegmentDO;
import io.invest.iagent.financial.model.SegmentValueDO;
import io.invest.iagent.financial.model.StatementType;
import io.invest.iagent.financial.model.ValueType;
import io.invest.iagent.financial.repository.FinancialRepository;
import io.invest.iagent.rag.filing.model.FiscalPeriod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 财务数据查询服务：指标名解析 → 期间选择 → 取数 → 查询时派生比率 → 层级 Markdown 表格渲染。
 * 库中期间不足时通过 {@link FinancialIngestService} 自动补采。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class FinancialQueryService {

    @Autowired
    private FinancialRepository repository;

    @Autowired
    private MetricCatalog metricCatalog;

    @Autowired
    private FinancialIngestService ingestService;

    @Autowired
    private SegmentIngestor segmentIngestor;

    @Autowired
    private FinancialProperties properties;

    /**
     * 查询结果。
     */
    public record QueryResult(String ticker, String markdown, List<String> warnings, boolean hasData) {}

    /**
     * 查询财务数据并渲染为层级指标 × 期间的 Markdown 表格。
     *
     * @param ticker      股票代码（可带市场前缀，如 HK.00700 / 00700 / BABA）
     * @param metricNames 指标名/编码/别名列表；null 或空表示全部有值指标
     * @param periods     期间列表（2025Q1 / FY2024）；null 表示最近 N 期
     * @param periodType  期间口径 SINGLE_Q / CUMULATIVE / FY；null 默认 SINGLE_Q
     */
    public QueryResult query(String ticker, List<String> metricNames, List<String> periods, String periodType) {
        List<String> warnings = new ArrayList<>();
        PeriodType ptype = parsePeriodType(periodType, warnings);

        // 自动补采 + 全量取数（口径过滤在 load 内完成）
        Loaded loaded = load(ticker, ptype);
        String bare = loaded.ticker();
        CompanyDO company = loaded.company();

        // 解析指标
        List<String> metricCodes = resolveMetrics(metricNames, warnings);
        boolean explicitMetrics = metricNames != null && !metricNames.isEmpty();

        // 解析期间
        List<String> periodLabels = resolvePeriods(periods, ptype, warnings);

        // 全量行保留在网格中（派生比率需要分母指标），渲染时按 explicitCodes 控制行可见性
        List<MetricValueDO> rows = loaded.rows();

        if (rows.isEmpty()) {
            String msg = "未查询到 " + bare + " 的财务数据（口径 " + ptype + "）。"
                    + "请先调用 financial_data_build 采集，或检查股票代码/期间/口径。";
            return new QueryResult(bare, msg, warnings, false);
        }

        // 默认期间：该口径下最近 N 期
        List<String> selectedPeriods = periodLabels;
        if (selectedPeriods == null || selectedPeriods.isEmpty()) {
            selectedPeriods = latestPeriods(rows, properties.getDefaultQueryPeriods());
        } else {
            Set<String> available = new HashSet<>();
            rows.forEach(r -> available.add(r.getFiscalPeriod()));
            List<String> missing = selectedPeriods.stream().filter(p -> !available.contains(p)).toList();
            if (!missing.isEmpty()) {
                warnings.add("以下期间无数据: " + String.join(", ", missing));
            }
            selectedPeriods = selectedPeriods.stream().filter(available::contains).toList();
        }

        // 网格：period -> code -> row
        Map<String, Map<String, MetricValueDO>> grid = buildGrid(rows);
        // 查询时派生比率指标（毛利率/净利率/ROE 等）
        addDerivedRatios(bare, selectedPeriods, ptype, grid, warnings);

        String markdown = renderTable(bare, company, selectedPeriods, grid, explicitMetrics ? new LinkedHashSet<>(metricCodes) : null);
        return new QueryResult(bare, markdown, warnings, true);
    }

    /**
     * 指标目录树文本（供工具列出可用指标）。
     */
    public String listMetrics(String statementKey) {
        StatementType filter = null;
        if (statementKey != null && !statementKey.isBlank()) {
            try {
                filter = StatementType.fromCode(statementKey.trim());
            } catch (IllegalArgumentException e) {
                return "未知报表类型: " + statementKey + "（支持 income/balance/cashflow/derived）";
            }
        }
        StringBuilder sb = new StringBuilder("标准指标目录");
        if (filter != null) {
            sb.append("（").append(filter.getNameCn()).append("）");
        }
        sb.append("：\n");
        for (MetricDef d : metricCatalog.all().values()) {
            if (filter != null && d.statementType() != filter) {
                continue;
            }
            int level = metricCatalog.levelOf(d.getCode());
            sb.append("　".repeat(Math.max(0, level - 1)))
                    .append("- ").append(d.getCode())
                    .append("：").append(d.getNameCn());
            if (d.getAliases() != null && !d.getAliases().isEmpty()) {
                sb.append("（").append(String.join("、", d.getAliases().stream().limit(3).toList())).append("）");
            }
            if (d.isDerived()) {
                sb.append(" [派生]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // =========================================================
    //  Web 页面结构化查询（仪表盘 / 趋势 / 构成）
    // =========================================================

    /** 仪表盘指标点（编码/中文名/值/同比）。 */
    public record MetricPoint(String code, String name, BigDecimal value, BigDecimal yoy) {}

    /** 核心指标卡片：主指标值 + 附属指标（资产负债表卡片含资产/负债/权益三项）。 */
    public record MetricCard(String key, String title, String code,
                             BigDecimal value, BigDecimal yoy, List<MetricPoint> items) {}

    /** 分部指标点（指标编码/中文名/值/同比）。 */
    public record SegmentPoint(String code, String name, BigDecimal value, BigDecimal yoy) {}

    /** 业务分部卡片：一级分部 + 最近一个季度头条指标（收入/毛利/EBITA 等）。 */
    public record SegmentCard(String segmentCode, String segmentName, int level,
                              String period, List<SegmentPoint> points) {}

    /** 仪表盘结果：最近一个季度核心指标数值与同比，及业务分部指标。 */
    public record DashboardResult(String ticker, String companyName, String market, String currency,
                                  String period, List<String> periods,
                                  boolean hasData, String message, List<String> warnings,
                                  List<MetricCard> cards, List<SegmentCard> segmentCards) {}

    /** 趋势序列点：期间 + 值 + 同比(%)。 */
    public record TrendPoint(String period, BigDecimal value, BigDecimal yoy) {}

    /** 趋势结果：某指标最近 N 期序列。 */
    public record TrendResult(String ticker, String code, String name, String unit, String periodType,
                              boolean hasData, String message, List<String> warnings,
                              List<TrendPoint> points) {}

    /** 构成表单元格：指标值 + 同比 + 占基准比例(%)。 */
    public record CompCell(BigDecimal value, BigDecimal yoy, BigDecimal ratio) {}

    /** 构成表行节点：分层指标 + 各期间单元格（与 periods 顺序对齐），target 为目标指标。 */
    public record TreeNode(String code, String name, String unit, String valueType, boolean derived,
                           boolean target, List<TreeNode> children, List<CompCell> cells) {}

    /** 构成结果：目标指标所属报表的分层指标 × 最近 N 期表格（periods 逆序，最新在前）。 */
    public record CompositionResult(String ticker, String code, String name, String statement,
                                    String currency, String ratioBasis, List<String> periods,
                                    boolean hasData, String message, List<String> warnings,
                                    List<TreeNode> tree) {}

    /** 分部构成表单元格：指标值 + 同比(%)。 */
    public record SegmentCell(BigDecimal value, BigDecimal yoy) {}

    /** 分部构成表行：某指标在各期间的值（与 periods 顺序对齐）。 */
    public record SegmentMetricRow(String code, String name, String unit, List<SegmentCell> cells) {}

    /**
     * 下级分部区块（可嵌套）：子分部编码/中文名/层级 + 该分部各指标行（期间与外层 periods 对齐）
     * + 其直属下级分部区块（整棵子树，供前端折叠/展开，无需逐层请求）。
     */
    public record SegmentSection(String segmentCode, String segmentName, int level,
                                 List<SegmentMetricRow> rows, List<SegmentSection> children) {}

    /** 分部构成结果：某业务分部全部指标 × 历年期间表格（periods 逆序，最新在前），含直属下级分部区块。 */
    public record SegmentCompositionResult(String ticker, String segmentCode, String segmentName,
                                           String currency, List<String> periods,
                                           boolean hasData, String message, List<String> warnings,
                                           List<SegmentMetricRow> rows, List<SegmentSection> children) {}

    /** 仪表盘核心指标卡片定义：key/标题/主指标编码/附属指标编码。 */
    private record CardSpec(String key, String title, String code, List<String> itemCodes) {}

    /** 仪表盘四张核心卡片：净利润、经营利润、自由现金流（经营现金流−资本开支）、资产负债表（资产/负债/权益）。 */
    private static final List<CardSpec> CORE_CARDS = List.of(
            new CardSpec("net_income", "净利润", "NET_INCOME", List.of()),
            new CardSpec("operating_income", "经营利润", "OPERATING_INCOME", List.of()),
            new CardSpec("free_cash_flow", "自由现金流", "FREE_CASH_FLOW", List.of()),
            new CardSpec("balance_sheet", "资产负债表", "TOTAL_ASSETS",
                    List.of("TOTAL_ASSETS", "TOTAL_LIABILITIES", "TOTAL_EQUITY")));

    /**
     * Web 仪表盘：最近一个季度核心指标（净利润/经营利润/自由现金流/资产负债表）的数值与同比。
     */
    public DashboardResult dashboard(String ticker) {
        List<String> warnings = new ArrayList<>();
        // 取全部口径数据：仅披露半年报/年报的公司（如港股 09992）没有单季流量值，
        // 需按公司实际披露口径自适应选择 SINGLE_Q / CUMULATIVE / FY，否则净利润等卡片会为空。
        Loaded loaded = loadAll(ticker);
        String bare = loaded.ticker();
        CompanyDO company = loaded.company();
        if (loaded.rows().isEmpty()) {
            return new DashboardResult(bare, companyName(company), companyMarket(company), companyCurrency(company),
                    null, List.of(), false,
                    "未查询到 " + bare + " 的财务数据，请先调用 financial_data_build 采集。", warnings, List.of(), List.of());
        }

        // 流量指标口径按「最新流量期间」自适应；资产负债表 STOCK 时点数任何口径都保留
        //（季报为 SINGLE_Q、年报为 FY，期间标签互不冲突），保证资产负债卡片在半年报口径下也有数。
        PeriodType flowType = selectFlowPeriodType(loaded.rows());
        List<MetricValueDO> rows = new ArrayList<>();
        for (MetricValueDO r : loaded.rows()) {
            if (isStockMetric(r.getMetricCode()) || flowType.name().equals(r.getPeriodType())) {
                rows.add(r);
            }
        }

        List<String> periods = sortedPeriods(rows);
        String latest = periods.get(periods.size() - 1);
        Map<String, Map<String, MetricValueDO>> grid = buildGrid(rows);
        // 最新一期及其去年同期派生指标（自由现金流/比率），后者用于卡片同比
        FiscalPeriod latestFp = FiscalPeriod.parse(latest);
        List<String> derivePeriods = latestFp == null ? List.of(latest)
                : List.of(latest, latestFp.yearAgo().canonical());
        addDerivedRatios(bare, derivePeriods, flowType, grid, warnings);
        Map<String, MetricValueDO> cell = grid.getOrDefault(latest, Map.of());

        List<MetricCard> cards = new ArrayList<>();
        for (CardSpec spec : CORE_CARDS) {
            List<MetricPoint> items = new ArrayList<>();
            for (String itemCode : spec.itemCodes()) {
                MetricDef d = metricCatalog.get(itemCode);
                MetricValueDO v = cell.get(itemCode);
                items.add(new MetricPoint(itemCode, d == null ? itemCode : d.getNameCn(),
                        v == null ? null : v.getValue(), v == null ? null : v.getYoy()));
            }
            MetricValueDO mv = cell.get(spec.code());
            BigDecimal value = mv == null ? null : mv.getValue();
            BigDecimal yoy = mv == null ? null : mv.getYoy();
            // 查询时派生指标（如自由现金流）未存同比，由去年同期值现场计算
            if (yoy == null && value != null) {
                yoy = computeYoy(grid, latest, spec.code(), value);
            }
            cards.add(new MetricCard(spec.key(), spec.title(), spec.code(), value, yoy, items));
        }
        // 业务分部：一级分部最近一个季度的收入/毛利/EBITA（无分部数据时自动提取一次）。
        // 分部数据为可选增强，本地解析管线异常时不应影响核心指标卡片。
        List<SegmentCard> segmentCards = List.of();
        try {
            segmentCards = buildSegmentCards(bare, warnings);
        } catch (Exception e) {
            log.warn("加载业务分部数据失败（忽略，不影响核心指标）: ticker={}, {}", bare, e.getMessage());
        }
        return new DashboardResult(bare, companyName(company), companyMarket(company), companyCurrency(company),
                latest, periods, true, null, warnings, cards, segmentCards);
    }

    /** 分部头条利润指标候选：优先调整后 EBITA，缺失时回退营业利润。 */
    private static final List<String> SEGMENT_PROFIT_METRICS = List.of("ADJUSTED_EBITA", "OPERATING_INCOME");

    /**
     * 构建业务分部卡片：一级分部各自最近一个季度（剔除 FY 年报）的收入/毛利/利润指标。
     * 每个分部取它自身最新有值的期间（各分部最新期可能不一致，避免某分部落后一期即整卡丢失）；
     * 利润指标取 ADJUSTED_EBITA，若各分部均无则回退 OPERATING_INCOME；无任何利润数据则不展示该行。
     */
    private List<SegmentCard> buildSegmentCards(String bare, List<String> warnings) {
        List<SegmentDO> segments = loadSegments(bare, warnings);
        if (segments.isEmpty()) {
            return List.of();
        }
        List<SegmentValueDO> values = repository.querySegmentValues(bare, null);
        if (values.isEmpty()) {
            return List.of();
        }

        // 一级分部（parent 为空），按目录顺序；剔除 TOTAL/SKIP 合计行
        List<SegmentDO> topSegments = segments.stream()
                .filter(s -> (s.getParentCode() == null || s.getLevel() <= 1)
                        && !isTotalSegment(s.getSegmentCode()))
                .sorted(Comparator.comparingInt(SegmentDO::getSortOrder))
                .toList();

        // 每个分部各自的最近季度（季度/半年期 ordinal<5，剔除 FY 全年值）。
        // 各分部最新有值期间可能不一致：最新季报若未披露/未抽出某分部整行，该分部停留在上一期，
        // 用全局单一最新期取数会把这类分部整卡丢弃（如 BABA 最新期缺淘天/AIDC 集团合计行），
        // 因此按分部取各自最新有值期间。
        Map<String, String> segLatest = new HashMap<>();
        Map<String, Integer> segLatestKey = new HashMap<>();
        for (SegmentValueDO v : values) {
            FiscalPeriod fp = FiscalPeriod.parse(v.getFiscalPeriod());
            if (fp == null || fp.ordinal() >= 5) {
                continue;
            }
            Integer cur = segLatestKey.get(v.getSegmentCode());
            if (cur == null || fp.sortKey() > cur) {
                segLatestKey.put(v.getSegmentCode(), fp.sortKey());
                segLatest.put(v.getSegmentCode(), v.getFiscalPeriod());
            }
        }
        if (segLatest.isEmpty()) {
            return List.of();
        }
        // 各分部在其最新期 (指标 -> 值)
        Map<String, Map<String, SegmentValueDO>> segCell = new HashMap<>();
        for (SegmentValueDO v : values) {
            String latest = segLatest.get(v.getSegmentCode());
            if (latest != null && latest.equals(v.getFiscalPeriod())) {
                segCell.computeIfAbsent(v.getSegmentCode(), k -> new HashMap<>())
                        .put(v.getMetricCode(), v);
            }
        }

        // 头条指标：收入、毛利固定；利润类按各分部最新期是否披露二选一
        List<String> headline = new ArrayList<>(List.of("REVENUE", "GROSS_PROFIT"));
        for (String profitCode : SEGMENT_PROFIT_METRICS) {
            boolean has = topSegments.stream().anyMatch(s -> {
                SegmentValueDO v = segCell.getOrDefault(s.getSegmentCode(), Map.of()).get(profitCode);
                return v != null && v.getValue() != null;
            });
            if (has) {
                headline.add(profitCode);
                break;
            }
        }

        List<SegmentCard> cards = new ArrayList<>();
        for (SegmentDO seg : topSegments) {
            String segPeriod = segLatest.get(seg.getSegmentCode());
            Map<String, SegmentValueDO> cell = segCell.getOrDefault(seg.getSegmentCode(), Map.of());
            List<SegmentPoint> points = new ArrayList<>();
            boolean hasValue = false;
            for (String mc : headline) {
                SegmentValueDO v = cell.get(mc);
                MetricDef d = metricCatalog.get(mc);
                if (v != null && v.getValue() != null) {
                    hasValue = true;
                }
                points.add(new SegmentPoint(mc, d == null ? mc : d.getNameCn(),
                        v == null ? null : v.getValue(), v == null ? null : v.getYoy()));
            }
            if (hasValue && segPeriod != null) {
                cards.add(new SegmentCard(seg.getSegmentCode(), segmentDisplayName(seg), seg.getLevel(),
                        segPeriod, points));
            }
        }
        return cards;
    }

    /** 加载分部目录，库中为空时自动触发一次本地提取（需已下载财报且有分部配置）。 */
    private List<SegmentDO> loadSegments(String bare, List<String> warnings) {
        List<SegmentDO> segments = repository.findSegments(bare);
        if (segments.isEmpty()) {
            SegmentIngestor.SegmentResult sr = segmentIngestor.ingest(bare);
            warnings.addAll(sr.warnings());
            segments = repository.findSegments(bare);
        }
        return segments;
    }

    private static String segmentDisplayName(SegmentDO seg) {
        return seg.getSegmentName() == null ? seg.getSegmentCode() : seg.getSegmentName();
    }

    /** 合计/占位行（引擎一致性校验用），不作为业务分部展示。 */
    private static boolean isTotalSegment(String segmentCode) {
        return segmentCode != null && (segmentCode.equalsIgnoreCase("TOTAL")
                || segmentCode.equalsIgnoreCase("SKIP"));
    }

    /**
     * Web 趋势：某指标最近 N 期（默认 16，可调整）的数值与同比序列。
     * 库中未存同比时由去年同期值现场计算。
     */
    public TrendResult trend(String ticker, String metricName, Integer quarters, String periodType) {
        List<String> warnings = new ArrayList<>();
        PeriodType ptype = parsePeriodType(periodType, warnings);
        String bare = normalizeTicker(ticker);
        String code = metricCatalog.resolveCode(metricName == null ? "" : metricName);
        if (code == null) {
            return new TrendResult(bare, null, metricName, null, ptype.name(), false,
                    "无法识别指标: " + metricName, warnings, List.of());
        }
        MetricDef def = metricCatalog.get(code);

        // 仅半年报/年报的公司（如 09992）无单季流量值：默认 SINGLE_Q 时自动合并 H1(CUMULATIVE)+FY 展示
        DisplayLoad display = loadForDisplay(bare, ptype);
        Loaded loaded = display.loaded();
        if (loaded.rows().isEmpty()) {
            return new TrendResult(bare, code, def.getNameCn(), def.getUnit(), ptype.name(), false,
                    "未查询到 " + bare + " 的财务数据，请先采集。", warnings, List.of());
        }

        int n = quarters == null ? 16 : Math.max(1, Math.min(40, quarters));
        List<String> periods = latestPeriods(loaded.rows(), n);
        Map<String, Map<String, MetricValueDO>> grid = buildGrid(loaded.rows());
        // 派生比率指标（毛利率/ROE 等）需要现场计算
        addDerivedRatios(bare, periods, display.deriveType(), grid, warnings);

        List<TrendPoint> points = new ArrayList<>();
        for (String p : periods) {
            MetricValueDO v = grid.getOrDefault(p, Map.of()).get(code);
            BigDecimal value = v == null ? null : v.getValue();
            BigDecimal yoy = v == null ? null : v.getYoy();
            if (yoy == null && value != null) {
                yoy = computeYoy(grid, p, code, value);
            }
            points.add(new TrendPoint(p, value, yoy));
        }
        return new TrendResult(bare, code, def.getNameCn(), def.getUnit(), ptype.name(),
                true, null, warnings, points);
    }

    /**
     * Web 构成：目标指标所属报表（利润表/资产负债表/现金流量表）的分层指标表，
     * 列为最近 N 个季度（默认 8，逆序即最新期间在最前）；每个单元格含指标值与占基准比例
     *（利润表/现金流量表为占收入比，如毛利率、费用率；资产负债表为占总资产比），
     * 目标指标所在行高亮，便于理解指标的因子构成。
     */
    public CompositionResult composition(String ticker, String metricName, Integer quarters) {
        List<String> warnings = new ArrayList<>();
        String bare = normalizeTicker(ticker);
        String code = metricCatalog.resolveCode(metricName == null ? "" : metricName);
        if (code == null) {
            return new CompositionResult(bare, null, metricName, null, "", null, List.of(),
                    false, "无法识别指标: " + metricName, warnings, List.of());
        }
        MetricDef def = metricCatalog.get(code);

        // 仅半年报/年报的公司（如 09992）无单季流量值：自动合并 H1(CUMULATIVE)+FY 展示构成
        DisplayLoad display = loadForDisplay(bare, PeriodType.SINGLE_Q);
        Loaded loaded = display.loaded();
        CompanyDO company = loaded.company();
        if (loaded.rows().isEmpty()) {
            return new CompositionResult(bare, code, def.getNameCn(), def.getStatement(),
                    companyCurrency(company), null, List.of(), false,
                    "未查询到 " + bare + " 的财务数据，请先采集。", warnings, List.of());
        }

        int n = quarters == null ? 8 : Math.max(1, Math.min(40, quarters));
        List<String> asc = sortedPeriods(loaded.rows());
        List<String> recentAsc = asc.size() <= n ? asc : asc.subList(asc.size() - n, asc.size());
        // 逆序展示：最新期间在最前
        List<String> periods = new ArrayList<>(recentAsc);
        Collections.reverse(periods);

        StatementType st = def.statementType();
        boolean balance = st == StatementType.BALANCE;
        String basisCode = balance ? "TOTAL_ASSETS" : "REVENUE";
        String ratioBasis = balance ? "占总资产比" : "占收入比";

        Map<String, Map<String, MetricValueDO>> grid = buildGrid(loaded.rows());
        // 查询时派生指标（自由现金流等），保证存量数据的派生行也有值
        addDerivedRatios(bare, periods, display.deriveType(), grid, warnings);
        List<TreeNode> tree = buildStatementTree(st, grid, periods, basisCode, code);
        return new CompositionResult(bare, code, def.getNameCn(), def.getStatement(),
                companyCurrency(company), ratioBasis, periods, true, null, warnings, tree);
    }

    /**
     * 分部派生利润率指标：比率 code -> 分子指标 code（分母均为分部收入 REVENUE）。
     * 这类比率不入库，分部趋势/构成查询时按 分子/收入×100 现场计算。
     */
    private static final Map<String, String> SEGMENT_RATIO_NUMERATOR = Map.of(
            "GROSS_MARGIN", "GROSS_PROFIT",
            "EBITA_MARGIN", "ADJUSTED_EBITA",
            "OPERATING_MARGIN", "OPERATING_INCOME");

    /**
     * 分部派生利润率趋势：取该分部全部分部值，逐季度按 分子/收入×100 计算比率
     *（分子或收入缺失的期间留空并跳过），同比为相对去年同季比率的变动百分比。
     * 仅取季度/半年期（剔除 FY 全年值）。
     */
    private TrendResult segmentRatioTrend(String bare, String segmentCode, String segName,
                                          String code, MetricDef def, String numCode,
                                          Integer quarters, List<String> warnings) {
        // period -> metricCode -> 值（含该分部全部指标，供取分子与收入）
        Map<String, Map<String, SegmentValueDO>> grid = new HashMap<>();
        for (SegmentValueDO v : repository.querySegmentValues(bare, null)) {
            if (segmentCode.equals(v.getSegmentCode())) {
                grid.computeIfAbsent(v.getFiscalPeriod(), k -> new HashMap<>()).put(v.getMetricCode(), v);
            }
        }
        // 逐季度计算比率
        Map<String, BigDecimal> ratioByPeriod = new HashMap<>();
        for (Map.Entry<String, Map<String, SegmentValueDO>> e : grid.entrySet()) {
            Map<String, SegmentValueDO> cell = e.getValue();
            SegmentValueDO num = cell.get(numCode);
            SegmentValueDO rev = cell.get("REVENUE");
            if (num != null && rev != null && num.getValue() != null && rev.getValue() != null
                    && rev.getValue().signum() != 0) {
                ratioByPeriod.put(e.getKey(), num.getValue().multiply(BigDecimal.valueOf(100))
                        .divide(rev.getValue().abs(), 2, RoundingMode.HALF_UP));
            }
        }
        // 季度口径（ordinal<5），剔除 FY 年报，按时间升序
        List<String> qPeriods = ratioByPeriod.keySet().stream()
                .filter(p -> {
                    FiscalPeriod fp = FiscalPeriod.parse(p);
                    return fp != null && fp.ordinal() < 5;
                })
                .sorted(Comparator.comparingInt(p -> FiscalPeriod.parse(p).sortKey()))
                .toList();
        int n = quarters == null ? 16 : Math.max(1, Math.min(40, quarters));
        List<String> recent = qPeriods.size() <= n ? qPeriods : qPeriods.subList(qPeriods.size() - n, qPeriods.size());

        List<TrendPoint> points = new ArrayList<>();
        for (String p : recent) {
            BigDecimal value = ratioByPeriod.get(p);
            BigDecimal yoy = null;
            if (value != null) {
                FiscalPeriod fp = FiscalPeriod.parse(p);
                BigDecimal prior = fp == null ? null : ratioByPeriod.get(fp.yearAgo().canonical());
                if (prior != null && prior.signum() != 0) {
                    yoy = value.subtract(prior).multiply(BigDecimal.valueOf(100))
                            .divide(prior.abs(), 1, RoundingMode.HALF_UP);
                }
            }
            points.add(new TrendPoint(p, value, yoy));
        }
        boolean hasData = points.stream().anyMatch(p -> p.value() != null);
        return new TrendResult(bare, code, def.getNameCn(), def.getUnit(), "SINGLE_Q",
                hasData, hasData ? null : "未查询到分部「" + segName + "」的 " + def.getNameCn() + " 季度数据。",
                warnings, points);
    }

    /**
     * Web 分部趋势：某业务分部某指标最近 N 个季度（默认 16）的数值与同比序列。
     * 仅取季度/半年期（剔除 FY 全年值，避免与单季不可比），同比取采集时已算好的值。
     */
    public TrendResult segmentTrend(String ticker, String segmentCode, String metricName, Integer quarters) {
        List<String> warnings = new ArrayList<>();
        String bare = normalizeTicker(ticker);
        String code = metricCatalog.resolveCode(metricName == null ? "" : metricName);
        if (code == null) {
            return new TrendResult(bare, null, metricName, null, "SINGLE_Q", false,
                    "无法识别指标: " + metricName, warnings, List.of());
        }
        MetricDef def = metricCatalog.get(code);
        List<SegmentDO> segments = loadSegments(bare, warnings);
        SegmentDO seg = segments.stream().filter(s -> s.getSegmentCode().equals(segmentCode)).findFirst().orElse(null);
        String segName = seg == null ? segmentCode : segmentDisplayName(seg);

        // 分部派生利润率（毛利率/EBITA利润率/营业利润率）不入库，查询时按 分子/收入 现场计算
        String ratioNumCode = SEGMENT_RATIO_NUMERATOR.get(code);
        if (ratioNumCode != null) {
            return segmentRatioTrend(bare, segmentCode, segName, code, def, ratioNumCode, quarters, warnings);
        }

        List<SegmentValueDO> values = repository.querySegmentValues(bare, null).stream()
                .filter(v -> segmentCode.equals(v.getSegmentCode()) && code.equals(v.getMetricCode()))
                .toList();
        // 季度口径（ordinal<5），剔除 FY 年报，按时间升序
        List<String> qPeriods = values.stream()
                .map(SegmentValueDO::getFiscalPeriod).distinct()
                .filter(p -> {
                    FiscalPeriod fp = FiscalPeriod.parse(p);
                    return fp != null && fp.ordinal() < 5;
                })
                .sorted(Comparator.comparingInt(p -> FiscalPeriod.parse(p).sortKey()))
                .toList();
        int n = quarters == null ? 16 : Math.max(1, Math.min(40, quarters));
        List<String> recent = qPeriods.size() <= n ? qPeriods : qPeriods.subList(qPeriods.size() - n, qPeriods.size());

        Map<String, SegmentValueDO> byPeriod = new HashMap<>();
        for (SegmentValueDO v : values) {
            byPeriod.put(v.getFiscalPeriod(), v);
        }
        List<TrendPoint> points = new ArrayList<>();
        for (String p : recent) {
            SegmentValueDO v = byPeriod.get(p);
            points.add(new TrendPoint(p, v == null ? null : v.getValue(), v == null ? null : v.getYoy()));
        }
        boolean hasData = points.stream().anyMatch(p -> p.value() != null);
        return new TrendResult(bare, code, def.getNameCn(), def.getUnit(), "SINGLE_Q",
                hasData, hasData ? null : "未查询到分部「" + segName + "」的 " + def.getNameCn() + " 季度数据。",
                warnings, points);
    }

    /**
     * Web 分部构成：某业务分部全部指标 × 最近 N 期（默认 16，含季报与 FY 年报）的历年表格，
     * 期间逆序（最新在前），单元格为指标值与同比；行按指标目录顺序排列，全无值的行剪枝。
     * 同时附带直属下级业务分部区块（各自指标行共用同一期间窗），下级分部可继续下钻/查看趋势。
     */
    public SegmentCompositionResult segmentComposition(String ticker, String segmentCode, Integer quarters) {
        List<String> warnings = new ArrayList<>();
        String bare = normalizeTicker(ticker);
        List<SegmentDO> segments = loadSegments(bare, warnings);
        SegmentDO seg = segments.stream().filter(s -> s.getSegmentCode().equals(segmentCode)).findFirst().orElse(null);
        String segName = seg == null ? segmentCode : segmentDisplayName(seg);
        CompanyDO company = repository.findCompany(bare);

        // 全部分部值一次性取出，当前分部与下级分部各自过滤
        List<SegmentValueDO> allValues = repository.querySegmentValues(bare, null);
        List<SegmentValueDO> values = allValues.stream()
                .filter(v -> segmentCode.equals(v.getSegmentCode()))
                .toList();
        // 期间窗以当前分部及其整棵下级子树的并集为准：最新季报可能只披露下级分部、缺集团合计行
        //（如 BABA 最新期缺淘天/AIDC 合计行），仅按当前分部取期间会把下级已有的最新期整列丢掉
        Set<String> subtreeCodes = collectSubtreeSegmentCodes(segments, segmentCode);
        List<SegmentValueDO> windowValues = allValues.stream()
                .filter(v -> subtreeCodes.contains(v.getSegmentCode()))
                .toList();
        if (windowValues.isEmpty()) {
            return new SegmentCompositionResult(bare, segmentCode, segName, companyCurrency(company), List.of(),
                    false, "未查询到分部「" + segName + "」的数据。", warnings, List.of(), List.of());
        }

        int n = quarters == null ? 16 : Math.max(1, Math.min(40, quarters));
        List<String> asc = windowValues.stream()
                .map(SegmentValueDO::getFiscalPeriod).distinct()
                .filter(p -> FiscalPeriod.parse(p) != null)
                .sorted(Comparator.comparingInt(p -> FiscalPeriod.parse(p).sortKey()))
                .toList();
        List<String> recentAsc = asc.size() <= n ? asc : asc.subList(asc.size() - n, asc.size());
        // 逆序展示：最新期间在最前
        List<String> periods = new ArrayList<>(recentAsc);
        Collections.reverse(periods);

        String currency = windowValues.stream().map(SegmentValueDO::getCurrency)
                .filter(c -> c != null && !c.isBlank()).findFirst().orElse("");
        if (currency.isBlank()) {
            currency = companyCurrency(company);
        }

        List<SegmentMetricRow> rows = buildSegmentMetricRows(values, periods);

        // 下级业务分部首整棵子树（parent 链接递归，按目录顺序）：共用同一期间窗，
        // 前端据此折叠/展开多层分部，无需逐层请求覆盖页面
        List<SegmentSection> children = buildSegmentSections(segments, allValues, periods, segmentCode);

        return new SegmentCompositionResult(bare, segmentCode, segName, currency, periods,
                true, null, warnings, rows, children);
    }

    /** 收集 rootCode 及其整棵下级子树的分部编码（沿 parentCode 链接递归）。 */
    private Set<String> collectSubtreeSegmentCodes(List<SegmentDO> segments, String rootCode) {
        Set<String> codes = new LinkedHashSet<>();
        codes.add(rootCode);
        boolean added = true;
        while (added) {
            added = false;
            for (SegmentDO s : segments) {
                if (s.getParentCode() != null && codes.contains(s.getParentCode())
                        && codes.add(s.getSegmentCode())) {
                    added = true;
                }
            }
        }
        return codes;
    }

    /**
     * 递归构建下级分部区块树：取 parentCode = parentCode 的直属子分部（按目录顺序，剔除合计行），
     * 各自挂载同一期间窗的指标行并递归其子分部；自身无指标行但有下级的纯分组节点也保留。
     */
    private List<SegmentSection> buildSegmentSections(List<SegmentDO> segments,
                                                      List<SegmentValueDO> allValues,
                                                      List<String> periods, String parentCode) {
        List<SegmentSection> result = new ArrayList<>();
        segments.stream()
                .filter(s -> parentCode.equals(s.getParentCode()) && !isTotalSegment(s.getSegmentCode()))
                .sorted(Comparator.comparingInt(SegmentDO::getSortOrder))
                .forEach(child -> {
                    List<SegmentValueDO> childValues = allValues.stream()
                            .filter(v -> child.getSegmentCode().equals(v.getSegmentCode()))
                            .toList();
                    List<SegmentMetricRow> childRows = buildSegmentMetricRows(childValues, periods);
                    List<SegmentSection> grandChildren =
                            buildSegmentSections(segments, allValues, periods, child.getSegmentCode());
                    if (!childRows.isEmpty() || !grandChildren.isEmpty()) {
                        result.add(new SegmentSection(child.getSegmentCode(),
                                segmentDisplayName(child), child.getLevel(), childRows, grandChildren));
                    }
                });
        return result;
    }

    /**
     * 构建某分部在指定期间窗内的指标行：行按指标目录顺序排列，全部期间无值的行剪枝。
     * 期间列由调用方给定（当前分部与下级分部共用同一期间窗，保证列对齐）。
     */
    private List<SegmentMetricRow> buildSegmentMetricRows(List<SegmentValueDO> segValues, List<String> periods) {
        // period -> metricCode -> 值
        Map<String, Map<String, SegmentValueDO>> grid = new LinkedHashMap<>();
        Set<String> metricCodes = new LinkedHashSet<>();
        for (SegmentValueDO v : segValues) {
            grid.computeIfAbsent(v.getFiscalPeriod(), k -> new LinkedHashMap<>()).put(v.getMetricCode(), v);
            metricCodes.add(v.getMetricCode());
        }
        List<String> orderedMetrics = metricCodes.stream()
                .sorted(Comparator.comparingInt(c -> {
                    MetricDef d = metricCatalog.get(c);
                    return d == null ? Integer.MAX_VALUE : d.getSortOrder();
                }))
                .toList();

        List<SegmentMetricRow> rows = new ArrayList<>();
        for (String mc : orderedMetrics) {
            MetricDef d = metricCatalog.get(mc);
            List<SegmentCell> cells = new ArrayList<>();
            boolean hasValue = false;
            for (String p : periods) {
                SegmentValueDO v = grid.getOrDefault(p, Map.of()).get(mc);
                if (v != null && v.getValue() != null) {
                    hasValue = true;
                }
                cells.add(new SegmentCell(v == null ? null : v.getValue(), v == null ? null : v.getYoy()));
            }
            if (hasValue) {
                rows.add(new SegmentMetricRow(mc, d == null ? mc : d.getNameCn(),
                        d == null ? "million" : d.getUnit(), cells));
            }
        }

        // 合成利润率行（查询时派生，非入库行）：同期收入与对应利润都有值时，紧跟利润行插入。
        // 毛利后插毛利率、营业利润后插营业利润率、调整后EBITA后插EBITA利润率；任一期可算即保留，否则剪枝。
        insertSegmentRowAfter(rows, "GROSS_PROFIT",
                buildSegmentRatioRow("GROSS_MARGIN", "GROSS_PROFIT", grid, periods));
        insertSegmentRowAfter(rows, "OPERATING_INCOME",
                buildSegmentRatioRow("OPERATING_MARGIN", "OPERATING_INCOME", grid, periods));
        insertSegmentRowAfter(rows, "ADJUSTED_EBITA",
                buildSegmentRatioRow("EBITA_MARGIN", "ADJUSTED_EBITA", grid, periods));
        return rows;
    }

    /**
     * 分部合成利润率行：ratioCode 的比率 = numCode / REVENUE × 100（各期间独立计算，缺分子/分母留空）。
     * 所有期间都无法计算时返回 null（按空行剪枝）。比率行无同比。
     */
    private SegmentMetricRow buildSegmentRatioRow(String ratioCode, String numCode,
                                                  Map<String, Map<String, SegmentValueDO>> grid,
                                                  List<String> periods) {
        MetricDef d = metricCatalog.get(ratioCode);
        List<SegmentCell> cells = new ArrayList<>();
        boolean hasValue = false;
        for (String p : periods) {
            Map<String, SegmentValueDO> cell = grid.getOrDefault(p, Map.of());
            SegmentValueDO num = cell.get(numCode);
            SegmentValueDO rev = cell.get("REVENUE");
            BigDecimal ratio = null;
            if (num != null && rev != null && num.getValue() != null && rev.getValue() != null
                    && rev.getValue().signum() != 0) {
                ratio = num.getValue().multiply(BigDecimal.valueOf(100))
                        .divide(rev.getValue().abs(), 2, RoundingMode.HALF_UP);
                hasValue = true;
            }
            cells.add(new SegmentCell(ratio, null));
        }
        return hasValue ? new SegmentMetricRow(ratioCode, d == null ? ratioCode : d.getNameCn(),
                "percent", cells) : null;
    }

    /** 在分部指标行中 anchorCode 之后插入 row；row 为 null 忽略，anchor 缺失时追加到末尾。 */
    private static void insertSegmentRowAfter(List<SegmentMetricRow> rows, String anchorCode, SegmentMetricRow row) {
        if (row == null) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (anchorCode.equals(rows.get(i).code())) {
                rows.add(i + 1, row);
                return;
            }
        }
        rows.add(row);
    }

    /**
     * 构成表中不按目录位置展示的指标：
     * EBITDA/ADJUSTED_EBITDA 为利润表中间口径，干扰净利润/营业利润的构成阅读；
     * NON_GAAP_CAPEX 为非 GAAP 补充口径，不作为投资活动子项展示，提升为自由现金流因子行「资本开支」。
     */
    private static final Set<String> COMPOSITION_HIDDEN_CODES =
            Set.of("EBITDA", "ADJUSTED_EBITDA", "NON_GAAP_CAPEX");

    /** 合成行编码：税率（所得税/税前利润），非入库指标，仅构成表展示。 */
    private static final String TAX_RATE_CODE = "EFFECTIVE_TAX_RATE";

    /**
     * 构建某张报表的分层指标表（目录顺序即行序），并挂载各期间值与占比。
     * 全部期间无值且无有效子节点的空行剪枝。构成表专属调整：
     * <ul>
     *   <li>利润表：隐藏 EBITDA 类中间口径；营业费用（含销售/管理/研发等子项）从营业总成本下移出，
     *       作为根级行排在毛利与营业利润之间（形成 毛利 − 营业费用 = 营业利润 的因子链）；
     *       毛利后插毛利率、营业费用后插营业费用率、营业利润后插营业利润率、所得税后插税率
     *       （均为查询时派生的合成行）；</li>
     *   <li>现金流量表：NON_GAAP_CAPEX 提升为自由现金流因子行「资本开支」，排在自由现金流之前
     *       （值取 NON_GAAP_CAPEX，缺失回退 GAAP CAPEX，与 FCF 派生口径一致）。</li>
     * </ul>
     */
    private List<TreeNode> buildStatementTree(StatementType statement,
                                              Map<String, Map<String, MetricValueDO>> grid,
                                              List<String> periods, String basisCode, String targetCode) {
        // 利润表中营业费用脱离营业总成本改挂根级（因子链展示需要），构建子节点时跳过该编码
        Set<String> detachedCodes = statement == StatementType.INCOME
                ? Set.of("OPERATING_EXPENSES") : Set.of();

        List<TreeNode> roots = new ArrayList<>();
        for (MetricDef d : metricCatalog.byStatement(statement)) {
            if (d.getParent() != null || COMPOSITION_HIDDEN_CODES.contains(d.getCode())) {
                continue;
            }
            TreeNode node = buildTreeNode(d, statement, grid, periods, basisCode, targetCode, detachedCodes);
            if (node != null) {
                roots.add(node);
            }
        }

        if (statement == StatementType.INCOME) {
            // 营业费用（连同销售/管理/研发等子项）先插到毛利之后；毛利率随后插到毛利之后，
            // 营业费用率插到营业费用之后，最终顺序为
            // 毛利 → 毛利率 → 营业费用 → 营业费用率 → 营业利润 → 营业利润率
            MetricDef opexDef = metricCatalog.get("OPERATING_EXPENSES");
            if (opexDef != null) {
                insertAfter(roots, "GROSS_PROFIT",
                        buildTreeNode(opexDef, statement, grid, periods, basisCode, targetCode, detachedCodes));
            }
            insertAfter(roots, "GROSS_PROFIT", buildRatioNode("GROSS_MARGIN", grid, periods, targetCode));
            insertAfter(roots, "OPERATING_EXPENSES",
                    buildRatioNode("OPERATING_EXPENSE_RATIO", grid, periods, targetCode));
            insertAfter(roots, "OPERATING_INCOME", buildRatioNode("OPERATING_MARGIN", grid, periods, targetCode));
            // 税率（所得税/税前利润）紧跟所得税行
            insertAfter(roots, "INCOME_TAX", buildTaxRateNode(grid, periods));
        } else if (statement == StatementType.CASHFLOW) {
            // 资本开支（自由现金流因子）插到自由现金流之前
            insertBefore(roots, "FREE_CASH_FLOW", buildCapexFactorNode(grid, periods, basisCode, targetCode));
        }
        return roots;
    }

    /** 在根级行中 anchorCode 之后插入 node；node 为 null 忽略，anchor 缺失时追加到末尾。 */
    private static void insertAfter(List<TreeNode> roots, String anchorCode, TreeNode node) {
        if (node == null) {
            return;
        }
        for (int i = 0; i < roots.size(); i++) {
            if (anchorCode.equals(roots.get(i).code())) {
                roots.add(i + 1, node);
                return;
            }
        }
        roots.add(node);
    }

    /** 在根级行中 anchorCode 之前插入 node；node 为 null 忽略，anchor 缺失时追加到末尾。 */
    private static void insertBefore(List<TreeNode> roots, String anchorCode, TreeNode node) {
        if (node == null) {
            return;
        }
        for (int i = 0; i < roots.size(); i++) {
            if (anchorCode.equals(roots.get(i).code())) {
                roots.add(i, node);
                return;
            }
        }
        roots.add(node);
    }

    /** 构建分层节点；全部期间无值且子节点也全部被剪枝时返回 null（空行删除）。 */
    private TreeNode buildTreeNode(MetricDef d, StatementType statement,
                                   Map<String, Map<String, MetricValueDO>> grid,
                                   List<String> periods, String basisCode, String targetCode,
                                   Set<String> detachedCodes) {
        if (COMPOSITION_HIDDEN_CODES.contains(d.getCode())) {
            return null;
        }
        // 比率指标不再计算占比；其余 FLOW/STOCK 指标与同期间基准相除
        boolean ratioable = !"percent".equals(d.getUnit()) && d.valueTypeEnum() != ValueType.RATIO;
        List<CompCell> cells = new ArrayList<>();
        boolean hasValue = false;
        for (String p : periods) {
            Map<String, MetricValueDO> cell = grid.getOrDefault(p, Map.of());
            MetricValueDO v = cell.get(d.getCode());
            BigDecimal value = v == null ? null : v.getValue();
            BigDecimal ratio = null;
            if (value != null) {
                hasValue = true;
                if (ratioable) {
                    MetricValueDO basis = cell.get(basisCode);
                    if (basis != null && basis.getValue() != null && basis.getValue().signum() != 0) {
                        ratio = value.multiply(BigDecimal.valueOf(100))
                                .divide(basis.getValue().abs(), 1, RoundingMode.HALF_UP);
                    }
                }
            }
            cells.add(new CompCell(value, v == null ? null : v.getYoy(), ratio));
        }

        List<TreeNode> children = new ArrayList<>();
        for (MetricDef child : metricCatalog.children(d.getCode())) {
            if (child.statementType() == statement && !detachedCodes.contains(child.getCode())) {
                TreeNode childNode = buildTreeNode(child, statement, grid, periods, basisCode, targetCode, detachedCodes);
                if (childNode != null) {
                    children.add(childNode);
                }
            }
        }

        // 全部期间无值且无有效子节点 → 空行剪枝
        if (!hasValue && children.isEmpty()) {
            return null;
        }
        return new TreeNode(d.getCode(), d.getNameCn(), d.getUnit(),
                d.getValueType() == null ? "FLOW" : d.getValueType(), d.isDerived(),
                d.getCode().equals(targetCode), children, cells);
    }

    /**
     * 合成比率行（毛利率/营业利润率）：取 grid 中查询时派生的比率值展示，非入库行；
     * 比率行不再计算占比、无同比。所有期间都无法计算（缺分子/分母）时返回 null（按空行剪枝）。
     */
    private TreeNode buildRatioNode(String code, Map<String, Map<String, MetricValueDO>> grid,
                                    List<String> periods, String targetCode) {
        MetricDef d = metricCatalog.get(code);
        if (d == null) {
            return null;
        }
        List<CompCell> cells = new ArrayList<>();
        boolean hasValue = false;
        for (String p : periods) {
            MetricValueDO v = grid.getOrDefault(p, Map.of()).get(code);
            BigDecimal value = v == null ? null : v.getValue();
            if (value != null) {
                hasValue = true;
            }
            cells.add(new CompCell(value, null, null));
        }
        return hasValue ? new TreeNode(code, d.getNameCn(), "percent", ValueType.RATIO.name(),
                true, code.equals(targetCode), List.of(), cells) : null;
    }

    /**
     * 自由现金流因子行「资本开支」：排在自由现金流行之前，值取 NON_GAAP_CAPEX（业绩公告经调整口径，
     * 与 FCF 派生口径一致），缺失时回退 GAAP CAPEX，保证 经营现金流 − 资本开支 = 自由现金流 可对账；
     * 占收入比与其他流量行一致。所有期间均无值时返回 null（按空行剪枝）。
     */
    private TreeNode buildCapexFactorNode(Map<String, Map<String, MetricValueDO>> grid,
                                          List<String> periods, String basisCode, String targetCode) {
        List<CompCell> cells = new ArrayList<>();
        boolean hasValue = false;
        for (String p : periods) {
            Map<String, MetricValueDO> cell = grid.getOrDefault(p, Map.of());
            MetricValueDO capex = cell.get("NON_GAAP_CAPEX");
            if (capex == null || capex.getValue() == null) {
                capex = cell.get("CAPEX");
            }
            BigDecimal value = capex == null ? null : capex.getValue();
            BigDecimal ratio = null;
            if (value != null) {
                hasValue = true;
                MetricValueDO basis = cell.get(basisCode);
                if (basis != null && basis.getValue() != null && basis.getValue().signum() != 0) {
                    ratio = value.multiply(BigDecimal.valueOf(100))
                            .divide(basis.getValue().abs(), 1, RoundingMode.HALF_UP);
                }
            }
            cells.add(new CompCell(value, capex == null ? null : capex.getYoy(), ratio));
        }
        return hasValue ? new TreeNode("NON_GAAP_CAPEX", "资本开支", "million", ValueType.FLOW.name(),
                false, "NON_GAAP_CAPEX".equals(targetCode), List.of(), cells) : null;
    }

    /**
     * 税率行（所得税/税前利润×100%）：紧跟所得税行展示的合成比率行，非入库指标。
     * 所有期间都无法计算（所得税或税前利润缺失）时返回 null（按空行剪枝）。
     */
    private TreeNode buildTaxRateNode(Map<String, Map<String, MetricValueDO>> grid, List<String> periods) {
        List<CompCell> cells = new ArrayList<>();
        boolean hasValue = false;
        for (String p : periods) {
            Map<String, MetricValueDO> cell = grid.getOrDefault(p, Map.of());
            MetricValueDO tax = cell.get("INCOME_TAX");
            MetricValueDO pretax = cell.get("PRETAX_INCOME");
            BigDecimal rate = null;
            if (tax != null && pretax != null && tax.getValue() != null && pretax.getValue() != null
                    && pretax.getValue().signum() != 0) {
                rate = tax.getValue().multiply(BigDecimal.valueOf(100))
                        .divide(pretax.getValue().abs(), 1, RoundingMode.HALF_UP);
                hasValue = true;
            }
            cells.add(new CompCell(rate, null, null));
        }
        return hasValue ? new TreeNode(TAX_RATE_CODE, "税率", "percent",
                ValueType.RATIO.name(), true, false, List.of(), cells) : null;
    }

    /** 由去年同期值计算同比(%)；基期缺失或为 0 返回 null。 */
    private BigDecimal computeYoy(Map<String, Map<String, MetricValueDO>> grid,
                                  String period, String code, BigDecimal value) {
        FiscalPeriod fp = FiscalPeriod.parse(period);
        if (fp == null) {
            return null;
        }
        MetricValueDO base = grid.getOrDefault(fp.yearAgo().canonical(), Map.of()).get(code);
        if (base == null || base.getValue() == null || base.getValue().signum() == 0) {
            return null;
        }
        return value.subtract(base.getValue())
                .multiply(BigDecimal.valueOf(100))
                .divide(base.getValue().abs(), 1, RoundingMode.HALF_UP);
    }

    private static String companyName(CompanyDO c) {
        return c == null ? null : c.getName();
    }

    private static String companyMarket(CompanyDO c) {
        return c == null ? null : c.getMarket();
    }

    private static String companyCurrency(CompanyDO c) {
        return c == null || c.getCurrency() == null ? "" : c.getCurrency();
    }

    /**
     * 查询分部业务数据并渲染 Markdown 表格（分部 × 指标 × 期间）。
     * 库中无分部数据时自动触发一次提取（需本地已有财报文件与分部配置）。
     */
    public QueryResult querySegments(String ticker, List<String> periods) {
        List<String> warnings = new ArrayList<>();
        String bare = normalizeTicker(ticker);

        List<SegmentDO> segments = loadSegments(bare, warnings);
        if (segments.isEmpty()) {
            return new QueryResult(bare,
                    "未查询到 " + bare + " 的分部数据。分部数据从本地财报文件提取：需先用 futu-filing 下载财报，"
                            + "且该公司在 segment-financial-report skill 中有分部配置。",
                    warnings, false);
        }

        List<String> periodLabels = resolveSegmentPeriods(periods, warnings);
        List<SegmentValueDO> values = repository.querySegmentValues(bare, periodLabels);
        if (values.isEmpty()) {
            return new QueryResult(bare, "未查询到 " + bare + " 指定期间的分部数据。", warnings, false);
        }

        // 默认期间：最近 N 期
        List<String> selectedPeriods = periodLabels;
        if (selectedPeriods == null || selectedPeriods.isEmpty()) {
            selectedPeriods = values.stream()
                    .map(SegmentValueDO::getFiscalPeriod)
                    .distinct()
                    .filter(p -> FiscalPeriod.parse(p) != null)
                    .sorted(Comparator.comparingInt(p -> FiscalPeriod.parse(p).sortKey()))
                    .toList();
            int n = properties.getDefaultQueryPeriods();
            if (selectedPeriods.size() > n) {
                selectedPeriods = selectedPeriods.subList(selectedPeriods.size() - n, selectedPeriods.size());
            }
        }

        String markdown = renderSegmentTable(bare, segments, values, selectedPeriods);
        return new QueryResult(bare, markdown, warnings, true);
    }

    private String renderSegmentTable(String ticker, List<SegmentDO> segments,
                                      List<SegmentValueDO> values, List<String> periods) {
        // (period, segmentCode, metricCode) -> value
        Map<String, SegmentValueDO> cell = new LinkedHashMap<>();
        String currency = "";
        for (SegmentValueDO v : values) {
            if (!periods.contains(v.getFiscalPeriod())) {
                continue;
            }
            cell.put(v.getFiscalPeriod() + "|" + v.getSegmentCode() + "|" + v.getMetricCode(), v);
            if (currency.isBlank() && v.getCurrency() != null) {
                currency = v.getCurrency();
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(ticker).append(" 分部业务数据");
        if (!currency.isBlank()) {
            sb.append("（金额单位：百万").append(currency).append("）");
        }
        sb.append("\n\n");
        sb.append("| 分部 | 指标 | ").append(String.join(" | ", periods)).append(" |\n");
        sb.append("| --- | --- |").append(" --- |".repeat(periods.size())).append("\n");

        // 每个分部实际有值的指标（按 catalog sortOrder 排序，未知编码排最后）
        Set<String> metricsUsed = new LinkedHashSet<>();
        for (SegmentValueDO v : values) {
            if (periods.contains(v.getFiscalPeriod())) {
                metricsUsed.add(v.getMetricCode());
            }
        }
        List<String> metricOrder = metricsUsed.stream()
                .sorted(Comparator.comparingInt(c -> {
                    MetricDef d = metricCatalog.get(c);
                    return d == null ? Integer.MAX_VALUE : d.getSortOrder();
                }))
                .toList();

        for (SegmentDO seg : segments) {
            for (String metricCode : metricOrder) {
                boolean hasValue = false;
                StringBuilder row = new StringBuilder();
                for (String p : periods) {
                    SegmentValueDO v = cell.get(p + "|" + seg.getSegmentCode() + "|" + metricCode);
                    row.append(" | ");
                    if (v != null && v.getValue() != null) {
                        hasValue = true;
                        row.append(formatMillion(v.getValue()));
                        if (v.getYoy() != null) {
                            row.append(" (").append(v.getYoy().setScale(1, RoundingMode.HALF_UP).toPlainString()).append("%)");
                        }
                    } else {
                        row.append("—");
                    }
                }
                if (!hasValue) {
                    continue;
                }
                String indent = "　".repeat(Math.max(0, seg.getLevel() - 1));
                MetricDef def = metricCatalog.get(metricCode);
                String metricName = def != null ? def.getNameCn() : metricCode;
                sb.append("| ").append(indent).append(seg.getSegmentName() == null ? seg.getSegmentCode() : seg.getSegmentName())
                        .append(" | ").append(metricName)
                        .append(row).append(" |\n");
            }
        }
        sb.append("\n说明：金额单位百万").append(currency).append("；括号内为同比(YoY%)；")
                .append("分部数据由财报文件解析（来源 SEGMENT_PARSE），与三大表 API 数据口径可能存在差异。");
        return sb.toString();
    }

    private List<String> resolveSegmentPeriods(List<String> periods, List<String> warnings) {
        if (periods == null || periods.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (String p : periods) {
            FiscalPeriod fp = FiscalPeriod.parse(p);
            String label = fp != null ? fp.canonical() : SegmentIngestor.canonicalPeriod(p);
            if (label == null) {
                warnings.add("无法识别期间: " + p + "（形如 2025Q1、FY2025、2025FY）");
                continue;
            }
            if (!labels.contains(label)) {
                labels.add(label);
            }
        }
        return labels;
    }

    // =========================================================
    //  解析
    // =========================================================

    private String normalizeTicker(String ticker) {
        return FutuCodeUtil.bareTicker(FutuCodeUtil.toFutuCode(ticker));
    }

    private PeriodType parsePeriodType(String raw, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            return PeriodType.SINGLE_Q;
        }
        try {
            return PeriodType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warnings.add("未知期间口径 " + raw + "，回退 SINGLE_Q（支持 SINGLE_Q/CUMULATIVE/FY）");
            return PeriodType.SINGLE_Q;
        }
    }

    /** 解析指标名 → 标准编码；null 表示全部。 */
    private List<String> resolveMetrics(List<String> names, List<String> warnings) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        List<String> codes = new ArrayList<>();
        for (String name : names) {
            String code = metricCatalog.resolveCode(name);
            if (code == null) {
                warnings.add("无法识别指标: " + name + "（可用 financial_data_metrics 查看指标目录）");
            } else if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** 解析期间参数 → 规范标签；null 表示未指定（走默认最近 N 期）。 */
    private List<String> resolvePeriods(List<String> periods, PeriodType ptype, List<String> warnings) {
        if (periods == null || periods.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (String p : periods) {
            FiscalPeriod fp = FiscalPeriod.parse(p);
            if (fp == null) {
                warnings.add("无法识别期间: " + p + "（形如 2025Q1、FY2024）");
                continue;
            }
            String label = fp.canonical();
            // 累计口径下 H1 半年报对应存储标签 Q2
            if (ptype == PeriodType.CUMULATIVE && label.contains("H1")) {
                label = fp.year() + "Q2";
            }
            if (!labels.contains(label)) {
                labels.add(label);
            }
        }
        return labels;
    }

    // =========================================================
    //  取数与派生
    // =========================================================

    private Map<String, Map<String, MetricValueDO>> buildGrid(List<MetricValueDO> rows) {
        Map<String, Map<String, MetricValueDO>> grid = new LinkedHashMap<>();
        for (MetricValueDO r : rows) {
            grid.computeIfAbsent(r.getFiscalPeriod(), k -> new LinkedHashMap<>())
                    .put(r.getMetricCode(), r);
        }
        return grid;
    }

    /** 该口径下最近 N 期（按时间升序返回）。 */
    private List<String> latestPeriods(List<MetricValueDO> rows, int n) {
        List<String> sorted = sortedPeriods(rows);
        return sorted.size() <= n ? sorted : sorted.subList(sorted.size() - n, sorted.size());
    }

    /** 全部期间按时间升序（无法解析的标签忽略）。 */
    private static List<String> sortedPeriods(List<MetricValueDO> rows) {
        return rows.stream()
                .map(MetricValueDO::getFiscalPeriod)
                .distinct()
                .filter(p -> FiscalPeriod.parse(p) != null)
                .sorted(Comparator.comparingInt(p -> FiscalPeriod.parse(p).sortKey()))
                .toList();
    }

    /** 数据加载结果：规范化代码、公司信息、该口径下全部指标行。 */
    private record Loaded(String ticker, CompanyDO company, List<MetricValueDO> rows) {}

    /** 自动补采（库中期间不足时先采集）并加载某口径下的全部指标行。 */
    private Loaded load(String ticker, PeriodType ptype) {
        Loaded all = loadAll(ticker);
        List<MetricValueDO> rows = new ArrayList<>(all.rows());
        // 口径过滤
        rows.removeIf(r -> !ptype.name().equals(r.getPeriodType()));
        return new Loaded(all.ticker(), all.company(), rows);
    }

    /** 自动补采（库中期间不足时先采集）并加载该公司全部口径的指标行（不过滤期间口径）。 */
    private Loaded loadAll(String ticker) {
        String bare = normalizeTicker(ticker);
        boolean backfilled = ingestService.backfillIfNeeded(bare);
        CompanyDO company = repository.findCompany(bare);
        if (company == null && !backfilled) {
            ingestService.build(bare, null);
            company = repository.findCompany(bare);
        }
        List<MetricValueDO> rows = repository.queryMetrics(bare, null, null);
        return new Loaded(bare, company, rows);
    }

    /** 指标是否为资产负债表时点数（STOCK）：时点值与披露口径无关，任何期间口径下都按其期间标签展示。 */
    private boolean isStockMetric(String metricCode) {
        MetricDef d = metricCatalog.get(metricCode);
        return d != null && d.valueTypeEnum() == ValueType.STOCK;
    }

    /**
     * 选择仪表盘流量指标（利润表/现金流量表）的期间口径。
     * <p>按 SINGLE_Q → CUMULATIVE → FY 顺序，取该口径下「最新流量期间」最靠后的口径；同期时优先 SINGLE_Q。
     * 仅披露半年报/年报的公司（如港股 09992）没有单季流量值，其 H1 累计（CUMULATIVE）最新期间晚于
     * 上一份年报（FY），故选 CUMULATIVE；季度披露的公司 SINGLE_Q 与 CUMULATIVE 最新期间相同，优先 SINGLE_Q。
     * 资产负债表 STOCK 时点数不参与口径选择（任何口径下都保留）。
     */
    private PeriodType selectFlowPeriodType(List<MetricValueDO> allRows) {
        PeriodType best = PeriodType.SINGLE_Q;
        int bestKey = -1;
        for (PeriodType pt : List.of(PeriodType.SINGLE_Q, PeriodType.CUMULATIVE, PeriodType.FY)) {
            int latest = -1;
            for (MetricValueDO r : allRows) {
                if (isStockMetric(r.getMetricCode()) || !pt.name().equals(r.getPeriodType())) {
                    continue;
                }
                FiscalPeriod fp = FiscalPeriod.parse(r.getFiscalPeriod());
                if (fp != null) {
                    latest = Math.max(latest, fp.sortKey());
                }
            }
            if (latest > bestKey) {
                bestKey = latest;
                best = pt;
            }
        }
        return best;
    }

    /** 是否存在单季流量值（利润表/现金流量表 SINGLE_Q 行）：季度披露公司为 true，仅半年报/年报公司为 false。 */
    private boolean hasSingleQuarterFlow(List<MetricValueDO> allRows) {
        return allRows.stream().anyMatch(r ->
                PeriodType.SINGLE_Q.name().equals(r.getPeriodType()) && !isStockMetric(r.getMetricCode()));
    }

    /** 展示类接口取数结果：数据行 + 派生比率口径（合并半年报/年报时用 FY，使年报单元格派生 ROE/ROA）。 */
    private record DisplayLoad(Loaded loaded, PeriodType deriveType) {}

    /**
     * 趋势/构成等展示接口取数：
     * <ul>
     *   <li>季度披露公司（存在单季流量值）：按请求口径取数（默认 SINGLE_Q），行为不变；</li>
     *   <li>仅披露半年报/年报的公司（如港股 09992，无任何单季流量值）且请求 SINGLE_Q 时：合并
     *       CUMULATIVE（H1 半年报）+ FY（年报）的流量行，并保留全部 STOCK 时点行（中报/年报资产负债表），
     *       使趋势图与构成表能按时间序展示半年报与年报数据；期间标签互不冲突（H1=yyyyQ2、年报=FYyyyy），
     *       YoY 由去年同期自然对齐（H1↔H1、FY↔FY）。派生口径取 FY 以便年报单元格计算 ROE/ROA。</li>
     * </ul>
     */
    private DisplayLoad loadForDisplay(String ticker, PeriodType requested) {
        Loaded all = loadAll(ticker);
        if (requested == PeriodType.SINGLE_Q && !hasSingleQuarterFlow(all.rows())) {
            List<MetricValueDO> rows = all.rows().stream()
                    .filter(r -> isStockMetric(r.getMetricCode())
                            || PeriodType.CUMULATIVE.name().equals(r.getPeriodType())
                            || PeriodType.FY.name().equals(r.getPeriodType()))
                    .toList();
            return new DisplayLoad(new Loaded(all.ticker(), all.company(), rows), PeriodType.FY);
        }
        List<MetricValueDO> rows = all.rows().stream()
                .filter(r -> requested.name().equals(r.getPeriodType()))
                .toList();
        return new DisplayLoad(new Loaded(all.ticker(), all.company(), rows), requested);
    }

    /**
     * 查询时派生指标：
     * 自由现金流 FCF = 经营现金流 OCF − 资本开支（资本开支优先取 Non-GAAP 经调整口径，缺失回退 GAAP）；
     * 比率指标（catalog 中 derived + RATIO）：毛利率/营业利润率/净利率/费用率为同期比率；ROE/ROA 仅年报口径（期末余额近似）。
     */
    private void addDerivedRatios(String ticker, List<String> periods, PeriodType ptype,
                                  Map<String, Map<String, MetricValueDO>> grid, List<String> warnings) {
        for (String period : periods) {
            Map<String, MetricValueDO> cell = grid.computeIfAbsent(period, k -> new LinkedHashMap<>());
            String currency = cell.values().stream().map(MetricValueDO::getCurrency)
                    .filter(c -> c != null).findFirst().orElse(null);

            deriveFreeCashFlow(cell, ticker, period, currency);
            ratioIfAbsent(cell, ticker, period, currency, "GROSS_MARGIN", "GROSS_PROFIT", "REVENUE");
            ratioIfAbsent(cell, ticker, period, currency, "OPERATING_EXPENSE_RATIO", "OPERATING_EXPENSES", "REVENUE");
            ratioIfAbsent(cell, ticker, period, currency, "OPERATING_MARGIN", "OPERATING_INCOME", "REVENUE");
            ratioIfAbsent(cell, ticker, period, currency, "NET_MARGIN", "NET_INCOME", "REVENUE");
            ratioIfAbsent(cell, ticker, period, currency, "SELLING_EXPENSE_RATIO", "SELLING_EXPENSE", "REVENUE");
            ratioIfAbsent(cell, ticker, period, currency, "GA_EXPENSE_RATIO", "GA_EXPENSE", "REVENUE");
            ratioIfAbsent(cell, ticker, period, currency, "RD_EXPENSE_RATIO", "RD_EXPENSE", "REVENUE");
            if (ptype == PeriodType.FY) {
                ratioIfAbsent(cell, ticker, period, currency, "ROE", "NET_INCOME", "EQUITY_PARENT");
                ratioIfAbsent(cell, ticker, period, currency, "ROA", "NET_INCOME", "TOTAL_ASSETS");
            }
        }
    }

    /**
     * 派生自由现金流 FCF = 经营现金流 OCF − 资本开支：
     * 资本开支优先取 Non-GAAP 经调整资本开支（NON_GAAP_CAPEX，业绩公告口径，RAG 提取），
     * 缺失时回退三大表 GAAP 资本开支（CAPEX）。每次查询现场重算并覆盖采集时按 GAAP 口径派生的存量 FCF 行，
     * 保证 FCF 卡片/趋势/构成统一使用最新口径；组件缺失无法计算时保留库存值。
     */
    private void deriveFreeCashFlow(Map<String, MetricValueDO> cell, String ticker,
                                    String period, String currency) {
        if (metricCatalog.get("FREE_CASH_FLOW") == null) {
            return;
        }
        MetricValueDO ocf = cell.get("OPERATING_CF");
        MetricValueDO capex = cell.get("NON_GAAP_CAPEX");
        if (capex == null || capex.getValue() == null) {
            capex = cell.get("CAPEX");
        }
        if (ocf == null || ocf.getValue() == null || capex == null || capex.getValue() == null) {
            return;
        }
        cell.put("FREE_CASH_FLOW", MetricValueDO.builder()
                .ticker(ticker).fiscalPeriod(period).periodType(ocf.getPeriodType())
                .metricCode("FREE_CASH_FLOW").value(ocf.getValue().subtract(capex.getValue()))
                .currency(currency).unit("million")
                .source(MetricSource.DERIVED.name())
                .build());
    }

    /** target 缺失且 numerator/denominator 均有值时，写入 target = num/den*100（百分比）。 */
    private void ratioIfAbsent(Map<String, MetricValueDO> cell, String ticker, String period,
                               String currency, String target, String numCode, String denCode) {
        if (cell.containsKey(target) || metricCatalog.get(target) == null) {
            return;
        }
        MetricValueDO num = cell.get(numCode);
        MetricValueDO den = cell.get(denCode);
        if (num == null || den == null || num.getValue() == null || den.getValue() == null
                || den.getValue().compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        BigDecimal pct = num.getValue().multiply(BigDecimal.valueOf(100))
                .divide(den.getValue(), 2, RoundingMode.HALF_UP);
        cell.put(target, MetricValueDO.builder()
                .ticker(ticker).fiscalPeriod(period).periodType(num.getPeriodType())
                .metricCode(target).value(pct).currency(currency).unit("percent")
                .source(MetricSource.DERIVED.name())
                .build());
    }

    // =========================================================
    //  渲染
    // =========================================================

    private String renderTable(String ticker, CompanyDO company, List<String> periods,
                               Map<String, Map<String, MetricValueDO>> grid, Set<String> explicitCodes) {
        String currency = company != null && company.getCurrency() != null ? company.getCurrency() : "";
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(ticker).append(" 财务数据");
        if (company != null && company.getMarket() != null) {
            sb.append("（").append(company.getMarket());
            if (currency != null && !currency.isBlank()) {
                sb.append("，金额单位：百万").append(currency);
            }
            sb.append("）");
        }
        sb.append("\n\n");

        // 表头
        sb.append("| 指标 | ").append(String.join(" | ", periods)).append(" |\n");
        sb.append("| --- |").append(" --- |".repeat(periods.size())).append("\n");

        // 行选择：显式指定 → 这些指标及其祖先；否则 → 有值的指标及其祖先
        Set<String> included = new LinkedHashSet<>();
        for (MetricDef d : metricCatalog.all().values()) {
            boolean wanted = explicitCodes != null ? explicitCodes.contains(d.getCode()) : hasValueAny(d.getCode(), periods, grid);
            if (wanted) {
                included.add(d.getCode());
                for (String p = d.getParent(); p != null; p = metricCatalog.get(p) == null ? null : metricCatalog.get(p).getParent()) {
                    included.add(p);
                }
            }
        }

        for (MetricDef d : metricCatalog.all().values()) {
            if (!included.contains(d.getCode())) {
                continue;
            }
            int level = metricCatalog.levelOf(d.getCode());
            sb.append("| ");
            sb.append("　".repeat(Math.max(0, level - 1)));
            sb.append(d.getNameCn() == null ? d.getCode() : d.getNameCn());
            for (String period : periods) {
                sb.append(" | ");
                MetricValueDO v = grid.getOrDefault(period, Map.of()).get(d.getCode());
                sb.append(formatCell(v, d));
            }
            sb.append(" |\n");
        }

        sb.append("\n说明：金额单位百万").append(currency).append("；比率为百分比；")
                .append("括号内为同比增速(YoY%)；[派生] 行由其他指标计算得出。");
        return sb.toString();
    }

    private boolean hasValueAny(String code, List<String> periods, Map<String, Map<String, MetricValueDO>> grid) {
        for (String p : periods) {
            MetricValueDO v = grid.getOrDefault(p, Map.of()).get(code);
            if (v != null && v.getValue() != null) {
                return true;
            }
        }
        return false;
    }

    private String formatCell(MetricValueDO v, MetricDef def) {
        if (v == null || v.getValue() == null) {
            return "—";
        }
        String text;
        if ("percent".equals(def.getUnit()) || ValueType.RATIO == def.valueTypeEnum()) {
            text = v.getValue().setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
        } else {
            text = formatMillion(v.getValue());
        }
        if (v.getYoy() != null) {
            text += " (" + v.getYoy().setScale(1, RoundingMode.HALF_UP).toPlainString() + "%)";
        }
        return text;
    }

    /** 百万金额：千分位、1 位小数、负数带负号。 */
    private static String formatMillion(BigDecimal v) {
        String plain = v.setScale(1, RoundingMode.HALF_UP).abs().toPlainString();
        int dot = plain.indexOf('.');
        String intPart = dot < 0 ? plain : plain.substring(0, dot);
        String frac = dot < 0 ? "0" : plain.substring(dot + 1);
        String intText = String.format(Locale.ROOT, "%,d", Long.parseLong(intPart));
        return (v.signum() < 0 ? "-" : "") + intText + "." + frac;
    }
}
