package io.invest.iagent.service.extraction.extractor;

import com.google.common.collect.Lists;
import io.invest.iagent.service.extraction.model.FinancialTable;
import io.invest.iagent.service.extraction.model.TableRow;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Optional;

public class PeriodTypeUtil {

    public static String determinePeriodType(FinancialTable table){
        String lowerTitle = Optional.ofNullable(table.getTitle()).map(String::toLowerCase).orElse("");
        String periodType = determinePeriodType(lowerTitle);
        // by headers
        if(StringUtils.isBlank(periodType)){
            periodType = Optional.ofNullable(table.getHeaders()).orElse(Lists.newArrayList())
                    .stream().map(PeriodTypeUtil::periodType)
                    .filter(StringUtils::isNotBlank)
                    .findFirst().orElse(null) ;
        }
        // by rows
        if(StringUtils.isBlank(periodType)){
            periodType = periodType(table.getRows()) ;
        }
        return periodType ;
    }


    private static String periodType(List<TableRow> rows){
        if(CollectionUtils.isEmpty(rows)){
            return null ;
        }
        // year
        boolean hasYearEnded = rows.stream()
                .anyMatch(row -> contains(row, "year ended"));
        if(hasYearEnded){
            return "FY" ;
        }
        // half
//        boolean hasHalfEnded = rows.stream()
//                .anyMatch(row -> contains(row, "six months ended"));
//        if(hasHalfEnded){
//            return "H" ;
//        }
        // quarter
        boolean hasQuarterEnded = rows.stream()
                .anyMatch(row -> contains(row, "three months ended"));
        if(hasQuarterEnded){
            if(rows.stream().anyMatch(r->contains(r,"march"))){
                return "Q1" ;
            } else if (rows.stream().anyMatch(r->contains(r,"june"))) {
                return "Q2" ;
            } else if (rows.stream().anyMatch(r->contains(r,"september"))) {
                return "Q3" ;
            } else if (rows.stream().anyMatch(r->contains(r,"december"))) {
                return "Q4" ;
            }
        }
        return null ;
    }

    private static boolean contains(TableRow row, String keyword){
        // label
        String label = Optional.ofNullable(row).map(TableRow::getLabel)
                .map(String::toLowerCase).orElse("") ;
        boolean contains = label.contains(keyword) ;
        if(contains){
            return true ;
        }
        // cells
        contains = Optional.ofNullable(row)
                .map(TableRow::getCells)
                .stream().anyMatch(cells->cells
                        .stream().filter(c-> c!=null && c.getText()!=null)
                        .anyMatch(cell -> cell.getText().toLowerCase().contains(keyword)));
        return contains ;
    }


    /**
     * 根据标题确定周期类型
     * Q1: 1-3月，Q2: 4-6月，Q3: 7-9月，Q4: 10-12月
     * H1: 上半年，H2: 下半年
     * FY: 全年/年报
     */
    private static String determinePeriodType(String lowerTitle) {
        if (lowerTitle == null || lowerTitle.isEmpty()) {
            return "";
        }

        // 判断季度
        if (lowerTitle.contains("three months") || lowerTitle.contains("quarter")) {
            if (lowerTitle.contains("march") || lowerTitle.contains("march 31") ||
                    lowerTitle.contains("february") || lowerTitle.contains("january")) {
                return "Q1";
            } else if (lowerTitle.contains("june") || lowerTitle.contains("may") ||
                    lowerTitle.contains("april")) {
                return "Q2";
            } else if (lowerTitle.contains("september") || lowerTitle.contains("august") ||
                    lowerTitle.contains("july")) {
                return "Q3";
            } else if (lowerTitle.contains("december") || lowerTitle.contains("november") ||
                    lowerTitle.contains("october")) {
                return "Q4";
            }
            // 默认Q1（很多公司财年从3月结束）
            return "Q1";
        }

        // 判断半年报
        if (lowerTitle.contains("six months") || lowerTitle.contains("six-month") ||
                lowerTitle.contains("half year")) {
            if (lowerTitle.contains("june") || lowerTitle.contains("first half")) {
                return "H1";
            }
            return "H2";
        }

        // 判断年报
        if (lowerTitle.contains("year ended") || lowerTitle.contains("fiscal year") ||
                lowerTitle.contains("twelve months") || lowerTitle.contains("12 months") ||
                lowerTitle.contains("full year")) {
            return "FY";
        }

        return "";
    }

    /**
     * 推断周期
     */
    private static String periodType(String header){
        if(StringUtils.isBlank(header)){
            return null ;
        }
        header = header.toLowerCase() ;
        if(header.contains("year ended")){
            return "FY" ;
        }
        if(header.contains("six months ended")){
            return "H" ;
        }
        if(header.contains("three months ended")){
            if(header.contains("march")){
                return "Q1" ;
            }else if(header.contains("june")){
                return "Q2" ;
            } else if (header.contains("september")) {
                return "Q3" ;
            }else if (header.contains("december")) {
                return "Q4";
            }
        }
        return null ;
    }

}
