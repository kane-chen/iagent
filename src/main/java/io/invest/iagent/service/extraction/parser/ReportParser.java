package io.invest.iagent.service.extraction.parser;


import io.invest.iagent.service.extraction.model.FinancialTable;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 财报解析器抽象基类
 */
public abstract class ReportParser {

    /**
     * 解析财报文件
     */
    public abstract List<FinancialTable> parse(File file) throws IOException;

    /**
     * 解析HTML内容
     */
    public abstract List<FinancialTable> parseHtml(String htmlContent);

    /**
     * 判断是否支持该文件格式
     */
    public abstract boolean supports(String format);

    /**
     * 从文件名获取格式
     */
    protected String getFileFormat(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            return "html";
        } else if (name.endsWith(".pdf")) {
            return "pdf";
        } else if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return "excel";
        }
        return "unknown";
    }
}
