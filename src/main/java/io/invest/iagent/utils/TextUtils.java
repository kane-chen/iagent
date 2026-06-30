package io.invest.iagent.utils;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

public class TextUtils {
    
    // 对应 Python 的 _normalize_whitespace
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * 标准化文本：压缩空白字符并转为小写。
     * 对应 Python 的 _normalize_statement_text
     */
    public static String normalizeStatementText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return WHITESPACE_PATTERN.matcher(value).replaceAll(" ").toLowerCase().trim();
    }

    /**
     * HTML 实体解码 (对应 Python html.unescape)。
     * 注意：Java 标准库没有直接的 unescape，这里简化处理，实际项目建议用 Apache Commons Text.
     */
    public static String unescapeHtml(String input) {
        if (input == null) return input;
        return input.replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'");
    }

    public static boolean contains(String filters,String value){
        return contains(filters,value,",",true) ;
    }

    public static boolean contains(String filters,String value,String split,boolean ignoreCase){
        if(StringUtils.isBlank(filters)){
            return true ;
        }
        if(StringUtils.isBlank(value)){
            return false ;
        }
        if(ignoreCase){
            filters = filters.toLowerCase() ;
            value = value.toLowerCase() ;
        }
        return (split + filters + split).contains(split+value+split) ;
    }
}