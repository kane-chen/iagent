package io.invest.iagent.utils;

import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

public class DateUtils {
    private static final DateTimeFormatter FORMATTER_DATE  = DateTimeFormatter.ofPattern("yyyy-MM-dd") ;

    /**
     * 解析日期字符串（支持yyyy / yyyy-MM / yyyy-MM-dd）
     */
    public static LocalDate parseDate(String dateStr) {
        if (StringUtils.isBlank(dateStr)) {
            return null;
        }
        if (dateStr.length() == 4) {
            return Year.parse(dateStr).atDay(1) ;
        } else if (dateStr.length() == 7) {
            return YearMonth.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM")).atDay(1);
        } else if (dateStr.length() == 10) {
            return LocalDate.parse(dateStr, FORMATTER_DATE);
        }
        throw new IllegalArgumentException("日期格式错误: " + dateStr + "（支持yyyy / yyyy-MM / yyyy-MM-dd）");
    }

    public static boolean inDateRange(String theDate , String fromDate, String toDate){
        if(StringUtils.isBlank(theDate)){
            return false ;
        }
        fromDate = formatQueryDate(fromDate,true) ;
        toDate = formatQueryDate(toDate,false) ;
        if(StringUtils.isNotBlank(fromDate) && theDate.compareTo(fromDate)<0){
            return false ;
        }
        if(StringUtils.isNotBlank(toDate) && theDate.compareTo(toDate)<0){
            return false ;
        }
        return true ;
    }

    public static String formatQueryDate(String date, boolean isStart){
        if(StringUtils.isBlank(date)){
            return null ;
        }
        if(date.length() == 4){
            if(isStart){
                return date + "-01-01" ;
            }else{
                return date + "-12-31" ;
            }
        }
        if(date.length() == 7){
            if(isStart){
                return date + "-01" ;
            }else{
                return date + "-31" ;
            }
        }
        if(date.length() == 10){
            return date ;
        }
        throw new IllegalArgumentException("日期格式错误: " + date + "（支持yyyy / yyyy-MM / yyyy-MM-dd）");
    }

}