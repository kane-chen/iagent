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
import io.invest.iagent.utils.PythonResolver;
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
import java.util.Comparator;
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
                PythonResolver.resolve(properties.getPythonExecutable()), script.toAbsolutePath().toString(),
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
                values.addAll(mapStatement(bare, currency, stmtKey, stmt, mapping, cumulativeMarket));
            }
        }

        // 港股/A股累计口径差分单季（财年可能跨自然年，需按财年结束月对齐累计链）
        if (cumulativeMarket) {
            values = differCumulative(values, fyeMonth);
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
                                             boolean cumulativeMarket) {
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

            boolean annual = periodText != null && periodText.toUpperCase().contains("FY");
            String canonical = canonicalPeriod(annual, periodEnd);
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
                // 资本开支按流出额（正数）存储：港股/美股现金流量表中购建支出字段按负数列报（流出为负，如 -95615），
                // 统一取绝对值，保证 FCF=OCF-CAPEX、累计差分符号一致；A股该字段本就为正，abs 无影响
                if ("CAPEX".equals(metricCode) && sum != null) {
                    sum = sum.abs();
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
     * 计算规范期间，统一按自然年标注（消除财报年与自然年不一致的阅读困难，
     * 如 BABA 财年截至 3 月：截至 2026-06 的季度标 2026Q2，而非财年口径 2027Q1）：
     * <ul>
     *   <li>年报 -> FY{结束日所在自然年}（年报结束月落在该年，如 BABA FY2026 截至 2026-03）；</li>
     *   <li>季报 -> {结束日自然年}Q{结束月所在自然季度}。</li>
     * </ul>
     */
    private String canonicalPeriod(boolean annual, String periodEnd) {
        LocalDate end = parseDate(periodEnd);
        if (end == null) {
            return null;
        }
        if (annual) {
            return "FY" + end.getYear();
        }
        int q = (end.getMonthValue() - 1) / 3 + 1;
        return end.getYear() + "Q" + q;
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
     * 累计值差分单季（兜底）：财年 Q1 单季=累计 Q1；Q2=累计 H1-累计 Q1；Q3=累计 9M-累计 H1；
     * Q4=年报 FY-累计 9M。仅对 FLOW 指标（利润表/现金流量表）生效；原 CUMULATIVE/FY 行保留。
     *
     * <p>期间标签统一为自然年后，财年可能跨自然年（如港股 09988 财年截至 3 月：财年 Q1 结束于 6 月、
     * 财年 Q4/年报结束于次年 3 月），同一财年的累计链不再落在同一日历年。这里按财年结束月 fyeMonth
     * 识别财年重启季度（财年 Q1 结束月 = fyeMonth+3），把年报行映射到其结束的自然季度 slot，
     * 沿自然季度时间序逐季差分，跨年链不断裂。
     *
     * <p>安全约束（修复单季被错算成累计值的问题）：
     * <ul>
     *   <li>累计链必须连续：缺少前置累计锚点时不得把累计值直接当单季（否则 Q2 单季 == H1 累计）；
     *       某季累计缺失则该季跳过，其后季度可在累计锚点恢复后继续差分；</li>
     *   <li>单季已由 ftype=1-4 单季报直取时，跳过失真（直取值优先）；</li>
     *   <li>差分的两个锚点必须属同一财年且相邻（相差一个季度）：跨财年（上年 FY → 新年 H1）差分出
     *       大额负数；同财年但缺季（仅半年报公司 H1→FY 横跨 H2 六个月）差分出多月金额——两者都不是
     *       单季，一律不输出。仅披露半年报/年报的公司（如港股 09992）没有季度数据，只保留 CUMULATIVE/FY。</li>
     * </ul>
     */
    private List<MetricValueDO> differCumulative(List<MetricValueDO> rows, int fyeMonth) {
        List<MetricValueDO> out = new ArrayList<>(rows);
        // 财年 Q1（重启季）结束的自然季度：结束月 = fyeMonth+3（跨年回绕）
        int restartEndMonth = fyeMonth + 3 > 12 ? fyeMonth - 9 : fyeMonth + 3;
        int restartQ = (restartEndMonth - 1) / 3 + 1;
        // 年报 FY{y} 结束于 fyeMonth 月，映射到该自然季度 slot
        int fySlotQ = (fyeMonth - 1) / 3 + 1;

        // 已存在的直取单季行（指标|期间）
        Set<String> existingSingle = new HashSet<>();
        // 指标 -> (自然季度 slot -> 年内累计值)：CUMULATIVE 行、重启季 SINGLE 锚点、FY 年报值
        Map<String, Map<String, BigDecimal>> ytdByMetric = new LinkedHashMap<>();
        // 指标 -> 模板行（取币种/单位）
        Map<String, MetricValueDO> templateByMetric = new HashMap<>();
        for (MetricValueDO r : rows) {
            if (r.getValue() == null) {
                continue;
            }
            String metric = r.getMetricCode();
            templateByMetric.putIfAbsent(metric, r);
            boolean isFy = PeriodType.FY.name().equals(r.getPeriodType());
            String slot;
            if (isFy) {
                String year = yearOf(r.getFiscalPeriod());
                if (year == null) {
                    continue;
                }
                slot = year + "Q" + fySlotQ;
            } else {
                slot = r.getFiscalPeriod();
            }
            if (PeriodType.SINGLE_Q.name().equals(r.getPeriodType())) {
                existingSingle.add(metric + "|" + r.getFiscalPeriod());
                // 财年重启季（财年 Q1）的单季值即新财年首个累计锚点
                if (("Q" + restartQ).equals(quarterTag(r.getFiscalPeriod()))) {
                    ytdByMetric.computeIfAbsent(metric, k -> new HashMap<>()).putIfAbsent(slot, r.getValue());
                }
            } else if (PeriodType.CUMULATIVE.name().equals(r.getPeriodType()) || isFy) {
                ytdByMetric.computeIfAbsent(metric, k -> new HashMap<>()).putIfAbsent(slot, r.getValue());
            }
        }

        // 为每个指标沿自然季度时间序补齐缺失的单季值
        for (Map.Entry<String, Map<String, BigDecimal>> e : ytdByMetric.entrySet()) {
            String metricCode = e.getKey();
            MetricDef def = metricCatalog.get(metricCode);
            if (def == null || def.valueTypeEnum() != ValueType.FLOW) {
                continue;
            }
            Map<String, BigDecimal> ytd = e.getValue();
            // 该指标出现过的全部自然季度 slot（直取单季 + 累计锚点），按时间升序
            Set<String> slotSet = new HashSet<>(ytd.keySet());
            for (String k : existingSingle) {
                int sep = k.indexOf('|');
                if (k.substring(0, sep).equals(metricCode)) {
                    slotSet.add(k.substring(sep + 1));
                }
            }
            List<String> slots = new ArrayList<>(slotSet);
            slots.sort(Comparator.comparingInt(p -> {
                int y = Integer.parseInt(yearOf(p));
                int q = Integer.parseInt(quarterTag(p).substring(1));
                return y * 10 + q;
            }));

            BigDecimal prev = null;   // 上一累计锚点值
            int prevFyKey = 0;        // 上一锚点所属财年 key
            int prevPos = 0;          // 上一锚点在财年内序号
            for (String slot : slots) {
                int q = Integer.parseInt(quarterTag(slot).substring(1));
                int pos = ((q - restartQ + 4) % 4) + 1; // 财年内序号：重启季=1
                int fyKey = fiscalYearKey(slot, fySlotQ);
                BigDecimal current = ytd.get(slot);
                if (existingSingle.contains(metricCode + "|" + slot)) {
                    // 单季报已直取，无需差分；累计锚点同步推进，锚点缺失则链断
                    prev = current;
                    prevFyKey = fyKey;
                    prevPos = pos;
                    continue;
                }
                if (current == null) {
                    // 本季累计缺失 → 本季无法差分，链断
                    prev = null;
                    prevPos = 0;
                    continue;
                }
                // 同一财年且与上一锚点相邻（相差恰好一个季度）才是合法单季差分：
                //  - 跨财年（上一锚点为上年 FY、本季为新年 H1）：H1本年 - FY上年 为大额负数，必须断链；
                //  - 同财年但锚点间缺季（如仅披露半年报：H1→FY 横跨 H2 六个月）：差分为多月金额，
                //    不是单季，不得输出（仅披露半年报/年报的公司本就无季度数据）。
                boolean sameFy = prev != null && fyKey == prevFyKey;
                if (pos == 1) {
                    // 财年重启季：3 个月累计即单季
                    out.add(buildSingleRow(templateByMetric.get(metricCode), slot, current));
                } else if (sameFy && pos == prevPos + 1) {
                    // 前置锚点存在且相邻：单季 = 本期累计 - 上期累计
                    out.add(buildSingleRow(templateByMetric.get(metricCode), slot, current.subtract(prev)));
                }
                // 否则跳过输出，但本季累计仍作为后续锚点（推进 prev）
                prev = current;
                prevFyKey = fyKey;
                prevPos = pos;
            }
        }
        return out;
    }

    /**
     * 计算自然季度 slot 所属财年的线性 key（= 该财年年报结束的自然季度序号 y*4+q）。
     * 同一财年的 Q1/H1/Q9/FY 锚点 key 相同；跨财年 key 不同，用于累计差分的财年边界判定。
     *
     * @param fySlotQ 年报(FY)结束的自然季度号（12 月财年=4；3 月财年=1）
     */
    private static int fiscalYearKey(String slot, int fySlotQ) {
        int y = Integer.parseInt(yearOf(slot));
        int q = Integer.parseInt(quarterTag(slot).substring(1));
        int nq = y * 4 + q;                 // 自然季度线性序号
        int delta = (fySlotQ - q + 4) % 4; // 推进到本财年 FY 结束季度所需季度数
        return nq + delta;
    }

    private MetricValueDO buildSingleRow(MetricValueDO template, String period, BigDecimal value) {
        return MetricValueDO.builder()
                .ticker(template.getTicker()).fiscalPeriod(period)
                .periodType(PeriodType.SINGLE_Q.name())
                .metricCode(template.getMetricCode()).value(value)
                .currency(template.getCurrency()).unit(template.getUnit())
                .source(MetricSource.FUTU_API.name())
                .build();
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
