package io.invest.iagent.service.filing.util;

import com.google.common.collect.Maps;
import io.invest.iagent.model.FinanceQueryParam;
import io.invest.iagent.model.FinancialIndexValueDTO;
import io.invest.iagent.service.filing.model.SecFilingDataDTO;
import io.invest.iagent.utils.TextUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class SecFilingFilterUtil {

    public static final String SOURCE_SEC_COMPANYFACTS = "SEC_COMPANYFACTS";

    public static List<FinancialIndexValueDTO> filter(SecFilingDataDTO filingData, FinanceQueryParam query, String currency, Map<String, Pair<String,String>> dict){
        if(Objects.isNull(filingData) || Objects.isNull(filingData.getFacts())){
            return null ;
        }
        // filter
        Map<String, SecFilingDataDTO.IndexItemDTO> values = filingData.getFacts().getUsGaap();
        values = filter(values,query,dict) ;
        // filter by date range
        if(Objects.isNull(values)){
            return null ;
        }
        List<FinancialIndexValueDTO> result = values.entrySet().stream()
                .map(v -> filterAndParse(v,query,currency,dict))
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .collect(Collectors.toList());
        return dedupe(result) ;
    }

    private static Map<String, SecFilingDataDTO.IndexItemDTO> filter(Map<String, SecFilingDataDTO.IndexItemDTO> items, FinanceQueryParam query, Map<String, Pair<String,String>> dict){
        if(Objects.isNull(items)){
            return null ;
        }
        if(Objects.isNull(query)){
            return items ;
        }
        return items.entrySet().stream()
                .filter(v -> match(v,query,dict))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue));
    }

    private static boolean match(Map.Entry<String,SecFilingDataDTO.IndexItemDTO> value, FinanceQueryParam query, Map<String, Pair<String,String>> dict){
        if(Objects.isNull(value)){
            return false ;
        }
        if(Objects.isNull(query)){
            return true ;
        }
        if(Objects.isNull(dict)){
            return true ;
        }
        Pair<String,String> pair = dict.get(value.getKey()) ;
        if(Objects.isNull(pair)){
            return false ;
        }
        boolean reportTypeMatch = TextUtils.contains(query.getReportTypes(),pair.getRight()) ;
        if(!reportTypeMatch){
            return false ;
        }
        return TextUtils.contains(query.getIndexCodes(),pair.getLeft()) ;
    }

    private static List<FinancialIndexValueDTO> filterAndParse(Map.Entry<String,SecFilingDataDTO.IndexItemDTO> entry,FinanceQueryParam query, String currency , Map<String, Pair<String,String>> dict){
        if(Objects.isNull(entry) || Objects.isNull(entry.getValue())){
            return null ;
        }
        Pair<String,String> pair = Optional.ofNullable(dict)
                .map(v->v.get(entry.getKey()))
                .orElse(Pair.of(null,null)) ;
        Map<String, List<SecFilingDataDTO.IndexValueDTO>> units = Optional.ofNullable(entry.getValue().getUnits()).orElse(Maps.newHashMap()) ;
        String theCurrency = getCurrency(units,currency) ;
        List<FinancialIndexValueDTO> result = new ArrayList<>() ;
        units.entrySet().stream()
                .filter(unit -> StringUtils.equals(unit.getKey(), theCurrency))
                .flatMap(unit -> Optional.ofNullable(unit.getValue()).orElse(List.of()).stream()
                        .filter(t -> match(t,query,pair.getLeft()))
                        .map(v -> FinancialIndexValueDTO.builder()
                                .ticker(query.getTicker())
                                .tableType(v.getForm())
                                .index(StringUtils.firstNonBlank(pair.getLeft(),entry.getKey()))
                                .value(v.getVal().toString())
                                .currency(unit.getKey())
                                .units(unit.getKey())
                                .period(v.getFrame())
                                .date(v.getFiled())
                                .fiscalYear(v.getFy())
                                .fiscalPeriod(v.getFp())
                                .startDate(v.getStart())
                                .endDate(v.getEnd())
                                .source(SOURCE_SEC_COMPANYFACTS)
                                .build()))
                .forEach(result::add) ;
        return result ;
    }

    private static String getCurrency(Map<String, List<SecFilingDataDTO.IndexValueDTO>> units, String currency ){
        if(StringUtils.isNotBlank(currency)){
            return currency ;
        }
        units = Optional.ofNullable(units).orElse(Maps.newHashMap()) ;
        if(!CollectionUtils.isEmpty(units.get("CNY"))){
            return "CNY" ;
        }
        if(!CollectionUtils.isEmpty(units.get("USD"))){
            return "USD" ;
        }
        return units.entrySet().stream()
                .filter(v -> !CollectionUtils.isEmpty(v.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null) ;
    }

    private static boolean match(SecFilingDataDTO.IndexValueDTO value, FinanceQueryParam query, String indexCode){
        if(Objects.isNull(value)){
            return false ;
        }
        if(Objects.isNull(query)){
            return true ;
        }
        // date match
        boolean fyMatch = TextUtils.contains(query.getFiscalYears(),String.valueOf(value.getFy())) ;
        if(!fyMatch){
            return false ;
        }
        // period
        boolean fpMatch = TextUtils.contains(query.getFiscalPeriods(),value.getFp()) ;
        if(!fpMatch){
            return false ;
        }
        // form
        boolean formMatch = TextUtils.contains(query.getFormTypes(), value.getForm()) ;
        if(!formMatch){
            return false ;
        }
        return durationMatches(value.getStart(), value.getEnd(), value.getFp(), indexCode) ;
    }

    public static boolean durationMatches(String start, String end, String fiscalPeriod, String indexCode){
        if(!isDurationMetric(indexCode)){
            return true ;
        }
        Long days = durationDays(start,end) ;
        if(Objects.isNull(days)){
            return true ;
        }
        if(StringUtils.equalsIgnoreCase("FY", fiscalPeriod)){
            return days >= 270 && days <= 380 ;
        }
        if(StringUtils.startsWithIgnoreCase(fiscalPeriod,"Q")){
            return days >= 70 && days <= 110 ;
        }
        return true ;
    }

    private static boolean isDurationMetric(String indexCode){
        if(StringUtils.isBlank(indexCode)){
            return true ;
        }
        return StringUtils.containsAnyIgnoreCase(indexCode,
                "Revenue","Cost","Expense","Income","Loss","Profit","Margin") ;
    }

    public static Long durationDays(String start, String end){
        if(StringUtils.isAnyBlank(start,end)){
            return null ;
        }
        try{
            return ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)) + 1 ;
        }catch (Exception e){
            return null ;
        }
    }

    public static List<FinancialIndexValueDTO> dedupe(List<FinancialIndexValueDTO> values){
        if(Objects.isNull(values)){
            return null ;
        }
        Map<String, FinancialIndexValueDTO> best = new LinkedHashMap<>() ;
        for(FinancialIndexValueDTO value : values){
            String key = String.join("|",
                    StringUtils.defaultString(value.getTicker()),
                    StringUtils.defaultString(value.getIndex()),
                    String.valueOf(value.getFiscalYear()),
                    StringUtils.defaultString(value.getFiscalPeriod()),
                    StringUtils.defaultString(value.getUnits())) ;
            best.merge(key,value,SecFilingFilterUtil::better) ;
        }
        return best.values().stream()
                .sorted(Comparator.comparing(FinancialIndexValueDTO::getIndex, Comparator.nullsLast(String::compareTo))
                        .thenComparing(FinancialIndexValueDTO::getFiscalYear, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(FinancialIndexValueDTO::getFiscalPeriod, Comparator.nullsLast(String::compareTo)))
                .toList() ;
    }

    private static FinancialIndexValueDTO better(FinancialIndexValueDTO left, FinancialIndexValueDTO right){
        int leftScore = score(left) ;
        int rightScore = score(right) ;
        if(leftScore != rightScore){
            return leftScore > rightScore ? left : right ;
        }
        String leftDate = StringUtils.defaultString(left.getDate()) ;
        String rightDate = StringUtils.defaultString(right.getDate()) ;
        return rightDate.compareTo(leftDate) > 0 ? right : left ;
    }

    private static int score(FinancialIndexValueDTO value){
        int score = 0 ;
        if(preferredForm(value)){
            score += 100 ;
        }
        if(StringUtils.isNotBlank(value.getPeriod())){
            score += 10 ;
        }
        score += sourceScore(value) ;
        score += periodYearScore(value) ;
        Long days = durationDays(value.getStartDate(), value.getEndDate()) ;
        if(Objects.nonNull(days)){
            if(StringUtils.equalsIgnoreCase("FY", value.getFiscalPeriod()) && days >= 270 && days <= 380){
                score += 5 ;
            }
            if(StringUtils.startsWithIgnoreCase(value.getFiscalPeriod(),"Q") && days >= 70 && days <= 110){
                score += 5 ;
            }
        }
        return score ;
    }

    private static int sourceScore(FinancialIndexValueDTO value){
        String source = StringUtils.defaultString(value.getSource()) ;
        if(StringUtils.equals(source, SOURCE_SEC_COMPANYFACTS)){
            return 6 ;
        }
        if(StringUtils.startsWith(source, "LOCAL_XBRL_LIMITED:")){
            return 4 ;
        }
        if(StringUtils.startsWith(source, "LOCAL_6K_HTML:")){
            return 2 ;
        }
        return 0 ;
    }

    private static int periodYearScore(FinancialIndexValueDTO value){
        if(Objects.isNull(value.getFiscalYear()) || StringUtils.isBlank(value.getEndDate())){
            return 0 ;
        }
        try{
            LocalDate end = LocalDate.parse(value.getEndDate()) ;
            int endYear = end.getYear() ;
            if(StringUtils.equalsIgnoreCase("FY", value.getFiscalPeriod())){
                return Math.max(0, 50 - Math.abs(value.getFiscalYear() - endYear) * 20) ;
            }
            if(StringUtils.startsWithIgnoreCase(value.getFiscalPeriod(),"Q")){
                int calendarYear = quarterCalendarYear(value.getFiscalYear(), value.getFiscalPeriod()) ;
                return Math.max(0, 50 - Math.abs(calendarYear - endYear) * 20) ;
            }
        }catch (Exception ignored){
            return 0 ;
        }
        return 0 ;
    }

    private static int quarterCalendarYear(Integer fiscalYear, String fiscalPeriod){
        // Most US issuers with fiscal years ending around Sep/Dec report FY Q1 in the prior calendar year.
        if(StringUtils.equalsAnyIgnoreCase(fiscalPeriod,"Q1","Q2","Q3")){
            return fiscalYear - 1 ;
        }
        return fiscalYear ;
    }

    private static boolean preferredForm(FinancialIndexValueDTO value){
        String form = value.getTableType() ;
        if(StringUtils.equalsIgnoreCase("FY", value.getFiscalPeriod())){
            return TextUtils.contains("10-K,20-F,40-F", form) ;
        }
        if(StringUtils.startsWithIgnoreCase(value.getFiscalPeriod(),"Q")){
            return TextUtils.contains("10-Q,6-K", form) ;
        }
        return false ;
    }

}
