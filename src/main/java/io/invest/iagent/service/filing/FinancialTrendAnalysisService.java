package io.invest.iagent.service.filing;

import io.invest.iagent.model.FinancialIndexValueDTO;
import io.invest.iagent.model.FinancialTrendAnalysisResult;
import io.invest.iagent.model.FinancialTrendRowDTO;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class FinancialTrendAnalysisService {

    private static final String DEFAULT_METRICS = "Revenue,CostOfRevenue,OperatingIncomeLoss";
    private final FinancialMetricsQueryService metricsQueryService;

    public FinancialTrendAnalysisService(FinancialMetricsQueryService metricsQueryService) {
        this.metricsQueryService = metricsQueryService;
    }

    public FinancialTrendAnalysisResult analyze(String ticker, String metrics, Integer quarterCount, String endFiscalYear,
                                                String sourcePreference) throws Exception {
        int limit = Objects.nonNull(quarterCount) && quarterCount > 0 ? quarterCount : 8;
        int endYear = parseEndYear(endFiscalYear);
        int yearsNeeded = Math.max(3, (int)Math.ceil((limit + 4) / 4.0) + 1);
        int startYear = endYear - yearsNeeded + 1;
        List<String> metricList = metricList(StringUtils.defaultIfBlank(metrics, DEFAULT_METRICS));
        List<FinancialIndexValueDTO> values = metricsQueryService.queryFinancialMetrics(
                ticker,
                String.join(",", metricList),
                String.valueOf(startYear),
                String.valueOf(endYear),
                "Q1,Q2,Q3,Q4",
                sourcePreference);
        return analyzeRows(StringUtils.upperCase(ticker), metricList, limit, values);
    }

    public FinancialTrendAnalysisResult analyzeRows(String ticker, List<String> metrics, int quarterCount, List<FinancialIndexValueDTO> values) {
        List<FinancialIndexValueDTO> quarterValues = values.stream()
                .filter(value -> StringUtils.startsWith(value.getFiscalPeriod(), "Q"))
                .filter(value -> value.getFiscalYear() != null)
                .sorted(rowComparator())
                .toList();
        List<String> periods = quarterValues.stream()
                .map(this::periodKey)
                .distinct()
                .sorted(this::comparePeriodKey)
                .toList();
        Set<String> selectedPeriods = periods.stream()
                .skip(Math.max(periods.size() - Math.max(quarterCount, 1), 0))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, FinancialIndexValueDTO> byMetricPeriod = quarterValues.stream()
                .collect(Collectors.toMap(value -> value.getIndex() + "|" + periodKey(value), value -> value, (left, right) -> right, LinkedHashMap::new));
        List<String> warnings = new ArrayList<>();
        List<FinancialTrendRowDTO> rows = new ArrayList<>();
        for(String period : selectedPeriods){
            String[] parts = period.split("-");
            int year = Integer.parseInt(parts[0]);
            String fiscalPeriod = parts[1];
            for(String metric : metrics){
                FinancialIndexValueDTO current = byMetricPeriod.get(metric + "|" + period);
                if(current == null){
                    rows.add(FinancialTrendRowDTO.builder()
                            .ticker(ticker)
                            .metric(metric)
                            .fiscalYear(year)
                            .fiscalPeriod(fiscalPeriod)
                            .missingReason("missing current period value")
                            .build());
                    warnings.add(metric + " missing for " + period);
                    continue;
                }
                FinancialIndexValueDTO base = byMetricPeriod.get(metric + "|" + (year - 1) + "-" + fiscalPeriod);
                rows.add(toTrendRow(current, base));
                if(base == null){
                    warnings.add(metric + " missing YoY base for " + period);
                }
            }
        }
        return FinancialTrendAnalysisResult.builder()
                .success(true)
                .ticker(ticker)
                .metrics(metrics)
                .quarterCount(quarterCount)
                .rows(rows)
                .warnings(warnings.stream().distinct().toList())
                .metadata(Map.of("period_count", selectedPeriods.size()))
                .build();
    }

    private FinancialTrendRowDTO toTrendRow(FinancialIndexValueDTO current, FinancialIndexValueDTO base) {
        BigDecimal value = decimal(current.getValue());
        BigDecimal baseValue = base == null ? null : decimal(base.getValue());
        BigDecimal change = value != null && baseValue != null ? value.subtract(baseValue) : null;
        BigDecimal changePercent = change != null && baseValue != null && baseValue.compareTo(BigDecimal.ZERO) != 0
                ? change.divide(baseValue.abs(), 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : null;
        return FinancialTrendRowDTO.builder()
                .ticker(current.getTicker())
                .metric(current.getIndex())
                .fiscalYear(current.getFiscalYear())
                .fiscalPeriod(current.getFiscalPeriod())
                .tableType(current.getTableType())
                .value(value)
                .currency(current.getCurrency())
                .units(current.getUnits())
                .startDate(current.getStartDate())
                .endDate(current.getEndDate())
                .source(current.getSource())
                .yoyBaseValue(baseValue)
                .yoyChange(change)
                .yoyChangePercent(changePercent)
                .yoySource(base == null ? null : base.getSource())
                .missingReason(base == null ? "missing prior-year same-quarter value" : null)
                .build();
    }

    private List<String> metricList(String metrics) {
        return List.of(metrics.split(",")).stream()
                .map(StringUtils::trimToNull)
                .filter(Objects::nonNull)
                .toList();
    }

    private int parseEndYear(String endFiscalYear) {
        if(StringUtils.isBlank(endFiscalYear)){
            return Year.now().getValue();
        }
        return Integer.parseInt(endFiscalYear.trim());
    }

    private String periodKey(FinancialIndexValueDTO value) {
        return value.getFiscalYear() + "-" + value.getFiscalPeriod();
    }

    private int comparePeriodKey(String left, String right) {
        String[] l = left.split("-");
        String[] r = right.split("-");
        int year = Integer.compare(Integer.parseInt(l[0]), Integer.parseInt(r[0]));
        if(year != 0){
            return year;
        }
        return Integer.compare(periodOrder(l[1]), periodOrder(r[1]));
    }

    private Comparator<FinancialIndexValueDTO> rowComparator() {
        return Comparator.comparing(FinancialIndexValueDTO::getFiscalYear, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(value -> periodOrder(value.getFiscalPeriod()))
                .thenComparing(FinancialIndexValueDTO::getEndDate, Comparator.nullsLast(String::compareTo));
    }

    private int periodOrder(String period) {
        return switch (StringUtils.defaultString(period)){
            case "Q1" -> 1;
            case "Q2" -> 2;
            case "Q3" -> 3;
            case "Q4" -> 4;
            default -> 9;
        };
    }

    private BigDecimal decimal(String value) {
        if(StringUtils.isBlank(value)){
            return null;
        }
        return new BigDecimal(value.trim());
    }
}
