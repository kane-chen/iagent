package io.invest.iagent.financial.ingest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.financial.config.FinancialProperties;
import io.invest.iagent.financial.config.FutuFieldMapping;
import io.invest.iagent.financial.model.CompanyDO;
import io.invest.iagent.financial.model.MetricCatalog;
import io.invest.iagent.financial.model.MetricDef;
import io.invest.iagent.financial.model.MetricSource;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.model.PeriodType;
import io.invest.iagent.financial.model.StatementType;
import io.invest.iagent.financial.model.ValueType;
import io.invest.iagent.utils.ProcessRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 三大表采集器：调用 futu-financial-report skill 的 fetch_statements_json.py 取数，
 * 经字段映射、累计差分（港股/A股）、派生指标计算后产出标准 {@link MetricValueDO}。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class FutuStatementIngestor {

    private static final String SCRIPT_REL = "skills/futu-financial-report/scripts/fetch_statements_json.py";
    private static final List<String> STATEMENT_KEYS = List.of("income", "balance", "cashflow");

    @Autowired
    private Path workspace;

    @Autowired
    private FinancialProperties properties;

    @Autowired
    private FutuFieldMapping fieldMapping;

    @Autowired
    private MetricCatalog metricCatalog;

    /**
     * 采集结果。
     */
    public record IngestResult(CompanyDO company, List<MetricValueDO> values,
                               List<String> errors, int periodCount) {}

    /**
     * 执行采集。
     *
     * @param ticker 裸 ticker（00700 / BABA）
     * @param num    拉取期数（每种报表）
     */
    public IngestResult ingest(String ticker, int num) throws Exception {
        String futuCode = FutuCodeUtil.toFutuCode(ticker);
        Path output = workspace.resolve("temp").resolve(
                futuCode.replace(".", "_") + "_statements.json");
        Files.createDirectories(output.getParent());

        Path script = workspace.resolve(SCRIPT_REL);
        List<String> cmd = List.of(
                properties.getPythonExecutable(), script.toAbsolutePath().toString(),
                futuCode, "--num", String.valueOf(num),
                "--output", output.toAbsolutePath().toString());
        ProcessRunner.Result result = ProcessRunner.run(cmd, null, properties.getPythonTimeoutSeconds());
        if (!result.isSuccess() || !Files.isRegularFile(output)) {
            throw new IllegalStateException("futu 取数脚本失败(rc=" + result.getExitCode() + "): "
                    + lastLines(result.getStderr(), 500));
        }

        JSONObject root = JSON.parseObject(Files.readString(output, StandardCharsets.UTF_8));
        return processRoot(root);
    }

    /**
     * 纯解析/映射逻辑：脚本输出 JSON → 标准指标行（不依赖外部进程，便于单元测试）。
     */
    IngestResult processRoot(JSONObject root) {
        String code = root.getString("code");
        String bare = FutuCodeUtil.bareTicker(code != null ? code : root.getString("ticker"));
        JSONObject errorsJson = root.getJSONObject("errors");
        List<String> errors = new ArrayList<>();
        if (errorsJson != null) {
            for (String k : errorsJson.keySet()) {
                errors.add(k + ": " + errorsJson.getString(k));
            }
        }

        String market = FutuCodeUtil.marketOf(code != null ? code : "");
        String currency = root.getString("currency");
        boolean cumulativeMarket = root.getBooleanValue("cumulative", false);
        int fyeMonth = detectFyeMonth(root);

        CompanyDO company = CompanyDO.builder()
                .ticker(bare).market(market).currency(currency).fyEndMonth(fyeMonth).build();

        FutuFieldMapping.MarketMapping mapping = fieldMapping.market(market);
        if (mapping == null) {
            throw new IllegalStateException("未配置市场字段映射: " + market);
        }

        List<MetricValueDO> values = new ArrayList<>();
        JSONObject statements = root.getJSONObject("statements");
        if (statements != null) {
            for (String stmtKey : STATEMENT_KEYS) {
                JSONObject stmt = statements.getJSONObject(stmtKey);
                if (stmt == null) {
                    continue;
                }
                values.addAll(mapStatement(bare, currency, stmtKey, stmt, mapping, cumulativeMarket, fyeMonth));
            }
        }

        // 港股/A股累计口径差分单季
        if (cumulativeMarket) {
            values = differCumulative(values);
        }
        // 派生指标：FCF = OCF - CapEx；毛利 = 收入 - 成本（API 未提供时）
        deriveMetrics(bare, currency, values);
        // 单季同比
        fillSingleQuarterYoy(values);

        long periodCount = values.stream()
                .map(MetricValueDO::getFiscalPeriod).distinct().count();
        log.info("Futu ingest done: ticker={}, market={}, values={}, periods={}, errors={}",
                bare, market, values.size(), periodCount, errors.size());
        return new IngestResult(company, values, errors, (int) periodCount);
    }

    // =========================================================
    //  映射
    // =========================================================

    /** 单张报表的 reports -> 标准指标行。 */
    private List<MetricValueDO> mapStatement(String ticker, String currency, String stmtKey,
                                             JSONObject stmt, FutuFieldMapping.MarketMapping mapping,
                                             boolean cumulativeMarket, int fyeMonth) {
        StatementType statementType = StatementType.fromCode(stmtKey);
        Map<String, List<Integer>> metricFields = mapping.statement(stmtKey);
        List<MetricValueDO> rows = new ArrayList<>();
        if (metricFields == null) {
            return rows;
        }

        JSONArray reports = stmt.getJSONArray("reports");
        if (reports == null) {
            return rows;
        }
        for (int i = 0; i < reports.size(); i++) {
            JSONObject rpt = reports.getJSONObject(i);
            String periodText = rpt.getString("period");
            String periodEnd = rpt.getString("periodEnd");
            Integer fiscalYear = rpt.getInteger("fiscalYear");

            boolean annual = periodText != null && periodText.toUpperCase().contains("FY");
            String canonical = canonicalPeriod(annual, periodEnd, fiscalYear, fyeMonth);
            if (canonical == null) {
                continue;
            }
            Integer ftype = rpt.getInteger("ftype");
            // 期间口径按 futu 财报类型判定：ftype 1-4=单季报（直取），5/6=Q6/Q9累计报，7=年报；
            // 无 ftype 的旧数据回退市场口径（美股单季；港股/A股累计，资产负债表为时点数按单季存）
            String periodType = resolvePeriodType(annual, ftype, statementType, cumulativeMarket);
            // Q1 单季报（3 个月）即年内累计 Q1：累计市场下同时写一份 CUMULATIVE，供累计口径查询
            boolean alsoCumulativeQ1 = cumulativeMarket && statementType != StatementType.BALANCE
                    && ftype != null && ftype == 1 && PeriodType.SINGLE_Q.name().equals(periodType);

            // fieldId -> (value, yoy)
            Map<Integer, BigDecimal> fieldValues = new HashMap<>();
            Map<Integer, BigDecimal> fieldYoy = new HashMap<>();
            Map<Integer, BigDecimal> fieldQoq = new HashMap<>();
            JSONArray items = rpt.getJSONArray("items");
            if (items != null) {
                for (int j = 0; j < items.size(); j++) {
                    JSONObject item = items.getJSONObject(j);
                    Integer fid = item.getInteger("fieldId");
                    if (fid == null) {
                        continue;
                    }
                    fieldValues.put(fid, item.getBigDecimal("value"));
                    fieldYoy.put(fid, item.getBigDecimal("yoy"));
                    fieldQoq.put(fid, item.getBigDecimal("qoq"));
                }
            }

            for (Map.Entry<String, List<Integer>> e : metricFields.entrySet()) {
                String metricCode = e.getKey();
                MetricDef def = metricCatalog.get(metricCode);
                if (def == null) {
                    continue;
                }
                BigDecimal sum = null;
                BigDecimal yoy = null;
                BigDecimal qoq = null;
                for (Integer fid : e.getValue()) {
                    BigDecimal v = fieldValues.get(fid);
                    if (v != null) {
                        sum = sum == null ? v : sum.add(v);
                        if (yoy == null) {
                            yoy = fieldYoy.get(fid);
                        }
                        if (qoq == null) {
                            qoq = fieldQoq.get(fid);
                        }
                    }
                }
                rows.add(buildRow(ticker, currency, canonical, periodType, metricCode, sum, yoy, qoq));
                if (alsoCumulativeQ1) {
                    rows.add(buildRow(ticker, currency, canonical, PeriodType.CUMULATIVE.name(),
                            metricCode, sum, yoy, qoq));
                }
            }
        }
        return rows;
    }

    private MetricValueDO buildRow(String ticker, String currency, String canonical, String periodType,
                                   String metricCode, BigDecimal value, BigDecimal yoy, BigDecimal qoq) {
        return MetricValueDO.builder()
                .ticker(ticker).fiscalPeriod(canonical).periodType(periodType)
                .metricCode(metricCode).value(value).yoy(yoy).qoq(qoq)
                .currency(currency).unit("million")
                .source(MetricSource.FUTU_API.name())
                .build();
    }

    // =========================================================
    //  期间规范化
    // =========================================================

    /**
     * 按 futu 财报类型（ftype）判定期间口径：
     * <ul>
     *   <li>年报（ftype=7 或期间文本含 FY）→ FY</li>
     *   <li>资产负债表为时点数（STOCK），非年报一律按单季标签存 SINGLE_Q</li>
     *   <li>ftype=5(Q6)/6(Q9) 累计报 → CUMULATIVE；ftype=1-4 单季报 → SINGLE_Q（直取，不做差分）</li>
     *   <li>无 ftype 的旧数据：美股单季；港股/A股利润表/现金流量表为累计</li>
     * </ul>
     */
    private static String resolvePeriodType(boolean annual, Integer ftype, StatementType statementType,
                                            boolean cumulativeMarket) {
        if (annual || (ftype != null && ftype == 7)) {
            return PeriodType.FY.name();
        }
        if (statementType == StatementType.BALANCE) {
            return PeriodType.SINGLE_Q.name();
        }
        if (ftype != null && (ftype == 5 || ftype == 6)) {
            return PeriodType.CUMULATIVE.name();
        }
        if (ftype != null && ftype >= 1 && ftype <= 4) {
            return PeriodType.SINGLE_Q.name();
        }
        return cumulativeMarket ? PeriodType.CUMULATIVE.name() : PeriodType.SINGLE_Q.name();
    }

    /** 从年报报告的截止日推断财年结束月份（默认 12）。 */
    private int detectFyeMonth(JSONObject root) {
        for (String stmtKey : STATEMENT_KEYS) {
            JSONObject stmt = root.getJSONObject("statements").getJSONObject(stmtKey);
            JSONArray reports = stmt == null ? null : stmt.getJSONArray("reports");
            if (reports == null) {
                continue;
            }
            for (int i = 0; i < reports.size(); i++) {
                JSONObject rpt = reports.getJSONObject(i);
                String periodText = rpt.getString("period");
                if (periodText != null && periodText.toUpperCase().contains("FY")) {
                    LocalDate end = parseDate(rpt.getString("periodEnd"));
                    if (end != null) {
                        return end.getMonthValue();
                    }
                }
            }
        }
        return 12;
    }

    /**
     * 计算规范期间：年报 -> FY{y}；季报 -> {fiscalYear}Q{q}。
     * 季度按财年结束月份偏移计算（与 segment skill 的 fiscal-year-end shift 一致）。
     */
    private String canonicalPeriod(boolean annual, String periodEnd, Integer fiscalYear, int fyeMonth) {
        LocalDate end = parseDate(periodEnd);
        if (end == null) {
            return null;
        }
        int cy = end.getYear();
        int m = end.getMonthValue();
        int fy = fiscalYear != null ? fiscalYear : (m <= fyeMonth ? cy : cy + 1);
        if (annual) {
            return "FY" + fy;
        }
        // 财年起点月 = fyeMonth+1；该季度是财年内第几个季度
        int monthsSinceStart = ((m - fyeMonth - 1) % 12 + 12) % 12 + 1;
        int q = (monthsSinceStart - 1) / 3 + 1;
        return fy + "Q" + q;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim().substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================
    //  累计差分（港股/A股）
    // =========================================================

    /**
     * 累计值差分单季（兜底）：Q1=累计Q1；Q2=累计Q2-累计Q1；Q3=累计Q3-累计Q2；Q4=FY-累计Q3。
     * 仅对 FLOW 指标（利润表/现金流量表）生效；原 CUMULATIVE/FY 行保留。
     * <p>安全约束（修复单季被错算成累计值的问题）：
     * <ul>
     *   <li>累计链必须连续：缺少 Q1 累计锚点时不得用 0 抵减（否则 Q2 单季 == H1 累计）；
     *       链中断则其后季度一律跳过；</li>
     *   <li>单季已由 ftype=1-4 单季报直取时，跳过失真（直取值优先）。</li>
     * </ul>
     */
    private List<MetricValueDO> differCumulative(List<MetricValueDO> rows) {
        List<MetricValueDO> out = new ArrayList<>(rows);
        // 已存在的直取单季行（指标|期间）
        Set<String> existingSingle = new HashSet<>();
        // key: metricCode|year -> {Q1/Q2/Q3/FY -> 年内累计值}
        Map<String, Map<String, BigDecimal>> ytdByMetricYear = new LinkedHashMap<>();
        for (MetricValueDO r : rows) {
            if (r.getValue() == null) {
                continue;
            }
            String year = yearOf(r.getFiscalPeriod());
            if (year == null) {
                continue;
            }
            String key = r.getMetricCode() + "|" + year;
            if (PeriodType.SINGLE_Q.name().equals(r.getPeriodType())) {
                existingSingle.add(r.getMetricCode() + "|" + r.getFiscalPeriod());
                // Q1 单季（3 个月）即年内累计 Q1，作为 Q2 差分锚点
                if ("Q1".equals(quarterTag(r.getFiscalPeriod()))) {
                    ytdByMetricYear.computeIfAbsent(key, k -> new HashMap<>()).putIfAbsent("Q1", r.getValue());
                }
            } else if (PeriodType.CUMULATIVE.name().equals(r.getPeriodType())) {
                String tag = quarterTag(r.getFiscalPeriod());
                if (tag != null) {
                    ytdByMetricYear.computeIfAbsent(key, k -> new HashMap<>()).putIfAbsent(tag, r.getValue());
                }
            } else if (PeriodType.FY.name().equals(r.getPeriodType())) {
                ytdByMetricYear.computeIfAbsent(key, k -> new HashMap<>()).putIfAbsent("FY", r.getValue());
            }
        }

        // 为每个 (指标, 财年) 补齐缺失的单季值
        for (Map.Entry<String, Map<String, BigDecimal>> e : ytdByMetricYear.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            String metricCode = parts[0];
            String year = parts[1];
            MetricDef def = metricCatalog.get(metricCode);
            if (def == null || def.valueTypeEnum() != ValueType.FLOW) {
                continue;
            }
            Map<String, BigDecimal> ytd = e.getValue();
            BigDecimal prev = BigDecimal.ZERO;
            for (int q = 1; q <= 4; q++) {
                String tag = q < 4 ? "Q" + q : "FY";
                BigDecimal current = ytd.get(tag);
                if (current == null) {
                    // 本期累计缺失 → 累计链断裂，后续季度无法继续差分
                    break;
                }
                String periodLabel = year + "Q" + q;
                if (existingSingle.contains(metricCode + "|" + periodLabel)) {
                    // 单季报已直取，无需差分，仅推进锚点
                    prev = current;
                    continue;
                }
                BigDecimal single = current.subtract(prev);
                MetricValueDO template = findTemplate(rows, metricCode, year, q);
                if (template != null) {
                    out.add(MetricValueDO.builder()
                            .ticker(template.getTicker()).fiscalPeriod(periodLabel)
                            .periodType(PeriodType.SINGLE_Q.name())
                            .metricCode(metricCode).value(single)
                            .currency(template.getCurrency()).unit(template.getUnit())
                            .source(MetricSource.FUTU_API.name())
                            .build());
                }
                prev = current;
            }
        }
        return out;
    }

    private MetricValueDO findTemplate(List<MetricValueDO> rows, String metricCode, String year, int q) {
        String wantPeriod = q < 4 ? year + "Q" + q : "FY" + year;
        for (MetricValueDO r : rows) {
            if (r.getMetricCode().equals(metricCode)
                    && (r.getFiscalPeriod().equals(wantPeriod) || r.getFiscalPeriod().equals("FY" + year))) {
                return r;
            }
        }
        return null;
    }

    // =========================================================
    //  派生指标
    // =========================================================

    private void deriveMetrics(String ticker, String currency, List<MetricValueDO> rows) {
        // (period, periodType) -> metric -> value
        Map<String, Map<String, MetricValueDO>> byPeriod = new LinkedHashMap<>();
        for (MetricValueDO r : rows) {
            byPeriod.computeIfAbsent(key(r), k -> new HashMap<>()).put(r.getMetricCode(), r);
        }
        List<MetricValueDO> derived = new ArrayList<>();
        for (Map.Entry<String, Map<String, MetricValueDO>> e : byPeriod.entrySet()) {
            Map<String, MetricValueDO> periodRows = e.getValue();
            String[] pk = e.getKey().split("\\|");
            String period = pk[0];
            String periodType = pk[1];

            addDerivedIfAbsent(derived, periodRows, ticker, currency, period, periodType,
                    "FREE_CASH_FLOW", "OPERATING_CF", "CAPEX", false);
            addDerivedIfAbsent(derived, periodRows, ticker, currency, period, periodType,
                    "GROSS_PROFIT", "REVENUE", "COST_OF_REVENUE", false);
        }
        rows.addAll(derived);
    }

    /** target 不存在且 a/b 均有值时，写入 target = a - b（或 a+b）。 */
    private void addDerivedIfAbsent(List<MetricValueDO> derived, Map<String, MetricValueDO> periodRows,
                                    String ticker, String currency, String period, String periodType,
                                    String target, String a, String b, boolean add) {
        if (periodRows.containsKey(target)) {
            return;
        }
        MetricValueDO ra = periodRows.get(a);
        MetricValueDO rb = periodRows.get(b);
        if (ra == null || rb == null || ra.getValue() == null || rb.getValue() == null) {
            return;
        }
        BigDecimal v = add ? ra.getValue().add(rb.getValue()) : ra.getValue().subtract(rb.getValue());
        derived.add(MetricValueDO.builder()
                .ticker(ticker).fiscalPeriod(period).periodType(periodType)
                .metricCode(target).value(v).currency(currency).unit("million")
                .source(MetricSource.DERIVED.name())
                .build());
    }

    // =========================================================
    //  单季同比
    // =========================================================

    private void fillSingleQuarterYoy(List<MetricValueDO> rows) {
        // (metric, period) -> row，仅 SINGLE_Q
        Map<String, MetricValueDO> single = new HashMap<>();
        for (MetricValueDO r : rows) {
            if (PeriodType.SINGLE_Q.name().equals(r.getPeriodType()) && r.getValue() != null) {
                single.put(r.getMetricCode() + "|" + r.getFiscalPeriod(), r);
            }
        }
        for (MetricValueDO r : single.values()) {
            String year = yearOf(r.getFiscalPeriod());
            String q = quarterTag(r.getFiscalPeriod());
            if (year == null || q == null) {
                continue;
            }
            MetricValueDO prior = single.get(r.getMetricCode() + "|" + (Integer.parseInt(year) - 1) + q);
            if (prior != null && prior.getValue() != null
                    && prior.getValue().compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal yoy = r.getValue().subtract(prior.getValue())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(prior.getValue().abs(), 2, RoundingMode.HALF_UP);
                r.setYoy(yoy);
            }
        }
    }

    // =========================================================
    //  工具
    // =========================================================

    private static String key(MetricValueDO r) {
        return r.getFiscalPeriod() + "|" + r.getPeriodType();
    }

    /** "2025Q2" -> 2025；"FY2025" -> 2025。 */
    static String yearOf(String period) {
        if (period == null) {
            return null;
        }
        if (period.startsWith("FY")) {
            return period.substring(2);
        }
        return period.length() >= 4 ? period.substring(0, 4) : null;
    }

    /** "2025Q2" -> "Q2"；FY -> "FY"。 */
    static String quarterTag(String period) {
        if (period == null) {
            return null;
        }
        if (period.startsWith("FY")) {
            return "FY";
        }
        int idx = period.indexOf('Q');
        return idx >= 0 ? period.substring(idx) : null;
    }

    private static String lastLines(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}
