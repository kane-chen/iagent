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

    /** 仪表盘结果：最近一个季度核心指标数值与同比。 */
    public record DashboardResult(String ticker, String companyName, String market, String currency,
                                  String period, List<String> periods,
                                  boolean hasData, String message, List<String> warnings,
                                  List<MetricCard> cards) {}

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

    /** 仪表盘核心指标卡片定义：key/标题/主指标编码/附属指标编码。 */
    private record CardSpec(String key, String title, String code, List<String> itemCodes) {}

    /** 仪表盘四张核心卡片：净利润、经营利润、资本开支、资产负债表（资产/负债/权益）。 */
    private static final List<CardSpec> CORE_CARDS = List.of(
            new CardSpec("net_income", "净利润", "NET_INCOME", List.of()),
            new CardSpec("operating_income", "经营利润", "OPERATING_INCOME", List.of()),
            new CardSpec("capex", "资本开支", "CAPEX", List.of()),
            new CardSpec("balance_sheet", "资产负债表", "TOTAL_ASSETS",
                    List.of("TOTAL_ASSETS", "TOTAL_LIABILITIES", "TOTAL_EQUITY")));

    /**
     * Web 仪表盘：最近一个季度核心指标（净利润/经营利润/资本开支/资产负债表）的数值与同比。
     */
    public DashboardResult dashboard(String ticker) {
        List<String> warnings = new ArrayList<>();
        Loaded loaded = load(ticker, PeriodType.SINGLE_Q);
        String bare = loaded.ticker();
        CompanyDO company = loaded.company();
        if (loaded.rows().isEmpty()) {
            return new DashboardResult(bare, companyName(company), companyMarket(company), companyCurrency(company),
                    null, List.of(), false,
                    "未查询到 " + bare + " 的财务数据，请先调用 financial_data_build 采集。", warnings, List.of());
        }

        List<String> periods = sortedPeriods(loaded.rows());
        String latest = periods.get(periods.size() - 1);
        Map<String, Map<String, MetricValueDO>> grid = buildGrid(loaded.rows());
        // 最新一期派生比率（卡片若为比率指标时可用）
        addDerivedRatios(bare, List.of(latest), PeriodType.SINGLE_Q, grid, warnings);
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
            cards.add(new MetricCard(spec.key(), spec.title(), spec.code(),
                    mv == null ? null : mv.getValue(), mv == null ? null : mv.getYoy(), items));
        }
        return new DashboardResult(bare, companyName(company), companyMarket(company), companyCurrency(company),
                latest, periods, true, null, warnings, cards);
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

        Loaded loaded = load(bare, ptype);
        if (loaded.rows().isEmpty()) {
            return new TrendResult(bare, code, def.getNameCn(), def.getUnit(), ptype.name(), false,
                    "未查询到 " + bare + " 的财务数据，请先采集。", warnings, List.of());
        }

        int n = quarters == null ? 16 : Math.max(1, Math.min(40, quarters));
        List<String> periods = latestPeriods(loaded.rows(), n);
        Map<String, Map<String, MetricValueDO>> grid = buildGrid(loaded.rows());
        // 派生比率指标（毛利率/ROE 等）需要现场计算
        addDerivedRatios(bare, periods, ptype, grid, warnings);

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

        Loaded loaded = load(bare, PeriodType.SINGLE_Q);
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
        List<TreeNode> tree = buildStatementTree(st, grid, periods, basisCode, code);
        return new CompositionResult(bare, code, def.getNameCn(), def.getStatement(),
                companyCurrency(company), ratioBasis, periods, true, null, warnings, tree);
    }

    /** 构建某张报表的分层指标表（目录顺序即行序），并挂载各期间值与占比。 */
    private List<TreeNode> buildStatementTree(StatementType statement,
                                              Map<String, Map<String, MetricValueDO>> grid,
                                              List<String> periods, String basisCode, String targetCode) {
        List<TreeNode> roots = new ArrayList<>();
        for (MetricDef d : metricCatalog.byStatement(statement)) {
            if (d.getParent() == null) {
                roots.add(buildTreeNode(d, statement, grid, periods, basisCode, targetCode));
            }
        }
        return roots;
    }

    private TreeNode buildTreeNode(MetricDef d, StatementType statement,
                                   Map<String, Map<String, MetricValueDO>> grid,
                                   List<String> periods, String basisCode, String targetCode) {
        // 比率指标不再计算占比；其余 FLOW/STOCK 指标与同期间基准相除
        boolean ratioable = !"percent".equals(d.getUnit()) && d.valueTypeEnum() != ValueType.RATIO;
        List<CompCell> cells = new ArrayList<>();
        for (String p : periods) {
            Map<String, MetricValueDO> cell = grid.getOrDefault(p, Map.of());
            MetricValueDO v = cell.get(d.getCode());
            BigDecimal ratio = null;
            if (ratioable && v != null && v.getValue() != null) {
                MetricValueDO basis = cell.get(basisCode);
                if (basis != null && basis.getValue() != null && basis.getValue().signum() != 0) {
                    ratio = v.getValue().multiply(BigDecimal.valueOf(100))
                            .divide(basis.getValue().abs(), 1, RoundingMode.HALF_UP);
                }
            }
            cells.add(new CompCell(v == null ? null : v.getValue(),
                    v == null ? null : v.getYoy(), ratio));
        }

        List<TreeNode> children = new ArrayList<>();
        for (MetricDef child : metricCatalog.children(d.getCode())) {
            if (child.statementType() == statement) {
                children.add(buildTreeNode(child, statement, grid, periods, basisCode, targetCode));
            }
        }
        return new TreeNode(d.getCode(), d.getNameCn(), d.getUnit(),
                d.getValueType() == null ? "FLOW" : d.getValueType(), d.isDerived(),
                d.getCode().equals(targetCode), children, cells);
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

        List<SegmentDO> segments = repository.findSegments(bare);
        if (segments.isEmpty()) {
            SegmentIngestor.SegmentResult sr = segmentIngestor.ingest(bare);
            warnings.addAll(sr.warnings());
            segments = repository.findSegments(bare);
        }
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
        String bare = normalizeTicker(ticker);
        boolean backfilled = ingestService.backfillIfNeeded(bare);
        CompanyDO company = repository.findCompany(bare);
        if (company == null && !backfilled) {
            ingestService.build(bare, null);
            company = repository.findCompany(bare);
        }
        List<MetricValueDO> rows = repository.queryMetrics(bare, null, null);
        // 口径过滤
        rows.removeIf(r -> !ptype.name().equals(r.getPeriodType()));
        return new Loaded(bare, company, rows);
    }

    /**
     * 查询时派生比率指标（catalog 中 derived + RATIO）：
     * 毛利率/营业利润率/净利率/费用率为同期比率；ROE/ROA 仅年报口径（期末余额近似）。
     */
    private void addDerivedRatios(String ticker, List<String> periods, PeriodType ptype,
                                  Map<String, Map<String, MetricValueDO>> grid, List<String> warnings) {
        for (String period : periods) {
            Map<String, MetricValueDO> cell = grid.computeIfAbsent(period, k -> new LinkedHashMap<>());
            String currency = cell.values().stream().map(MetricValueDO::getCurrency)
                    .filter(c -> c != null).findFirst().orElse(null);

            ratioIfAbsent(cell, ticker, period, currency, "GROSS_MARGIN", "GROSS_PROFIT", "REVENUE");
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
