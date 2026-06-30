package io.invest.iagent.service.extraction.parser;

import io.invest.iagent.service.extraction.model.FinancialTable;
import io.invest.iagent.service.extraction.model.TableCell;
import io.invest.iagent.service.extraction.model.TableRow;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * HTML财报解析器
 * 基于Jsoup解析HTML格式的财报文件
 */
@Slf4j
public class HtmlReportParser extends ReportParser {

    @Override
    public List<FinancialTable> parse(File file) throws IOException {
        log.info("Parsing HTML report file: {}", file.getName());
        Document doc = Jsoup.parse(file, "UTF-8");
        return extractTables(doc);
    }

    @Override
    public List<FinancialTable> parseHtml(String htmlContent) {
        log.info("Parsing HTML content, length: {}", htmlContent.length());
        Document doc = Jsoup.parse(htmlContent);
        return extractTables(doc);
    }

    @Override
    public boolean supports(String format) {
        return "html".equalsIgnoreCase(format) || "htm".equalsIgnoreCase(format);
    }

    /**
     * 从文档中提取所有财务表格
     */
    private List<FinancialTable> extractTables(Document doc) {
        List<FinancialTable> tables = new ArrayList<>();
        
        // 获取所有表格
        Elements tableElements = doc.select("table");
        log.info("Found {} tables in document", tableElements.size());

        int tableIndex = 0;
        for (Element tableElement : tableElements) {
            try {
                FinancialTable table = parseTable(tableElement, tableIndex);
                if (isValidFinancialTable(table)) {
                    tables.add(table);
                    log.debug("Extracted table: {} ({} rows, {} cols)", 
                            table.getTitle(), table.getRows().size(), table.getHeaders().size());
                }
            } catch (Exception e) {
                log.warn("Failed to parse table {}: {}", tableIndex, e.getMessage());
            }
            tableIndex++;
        }

        log.info("Successfully extracted {} financial tables", tables.size());
        return tables;
    }

    /**
     * 解析单个表格
     */
    private FinancialTable parseTable(Element tableElement, int tableIndex) {
        FinancialTable table = new FinancialTable();
        table.setTableId("table_" + tableIndex);

        // 尝试获取表格标题（前面的p或h标签）
        String title = findTableTitle(tableElement);
        table.setTitle(title);

        // 解析表头
        List<String> headers = parseHeaders(tableElement);
        table.setHeaders(headers);

        // 解析表体行
        List<TableRow> rows = parseTableRows(tableElement);
        table.setRows(rows);

        // 推断币种和单位
        inferCurrencyAndUnit(table);

        return table;
    }

    /**
     * 查找表格标题
     */
    private String findTableTitle(Element tableElement) {
        // 查找表格前面的标题元素
        Element prev = tableElement.previousElementSibling();
        while (prev != null) {
            String text = prev.text().trim();
            if (!text.isEmpty() && text.length() < 200) {
                // 检查是否像标题
                if (isLikelyTitle(text)) {
                    return text;
                }
            }
            prev = prev.previousElementSibling();
        }
        
        // 尝试从caption获取
        Element caption = tableElement.selectFirst("caption");
        if (caption != null) {
            return caption.text().trim();
        }

        return "Untitled Table";
    }

    /**
     * 判断是否像标题
     */
    private boolean isLikelyTitle(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // 包含这些关键词的很可能是财务表格标题
        String[] titleKeywords = {"revenue", "income", "segment", "profit", "loss", 
                "收入", "利润", "分部", "营业", "经营", "成本", "费用", "EBIT", "EBITDA"};
        String lowerText = text.toLowerCase();
        for (String keyword : titleKeywords) {
            if (lowerText.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析表头
     */
    private List<String> parseHeaders(Element tableElement) {
        List<String> headers = new ArrayList<>();
        
        // 优先从thead中获取
        Element thead = tableElement.selectFirst("thead");
        if (thead != null) {
            Elements thElements = thead.select("th");
            for (Element th : thElements) {
                headers.add(cleanText(th.text()));
            }
        }
        
        // 如果thead没有，从第一行tr获取
        if (headers.isEmpty()) {
            Element firstRow = tableElement.selectFirst("tr");
            if (firstRow != null) {
                Elements cells = firstRow.select("th, td");
                for (Element cell : cells) {
                    headers.add(cleanText(cell.text()));
                }
            }
        }

        return headers;
    }

    /**
     * 解析表格行
     */
    private List<TableRow> parseTableRows(Element tableElement) {
        List<TableRow> rows = new ArrayList<>();
        
        // 获取所有tr
        Elements trElements = tableElement.select("tr");
        
        // 跳过表头行（如果有thead的话）
        int startIndex = 0;
        Element thead = tableElement.selectFirst("thead");
        if (thead != null) {
            startIndex = thead.select("tr").size();
        } else if (!trElements.isEmpty()) {
            // 第一行作为表头
            startIndex = 1;
        }

        for (int i = startIndex; i < trElements.size(); i++) {
            Element trElement = trElements.get(i);
            TableRow row = parseTableRow(trElement);
            if (row != null) {
                rows.add(row);
            }
        }

        return rows;
    }

    /**
     * 解析单行
     */
    private TableRow parseTableRow(Element trElement) {
        TableRow row = new TableRow();
        
        Elements cells = trElement.select("td, th");
        if (cells.isEmpty()) {
            return null;
        }

        // 第一个单元格通常是标签
        Element firstCell = cells.first();
        String label = cleanText(firstCell.text());
        row.setLabel(label);

        // 计算缩进级别（通过第一个单元格的空格或嵌套）
        int indentLevel = calculateIndentLevel(firstCell);
        row.setIndentLevel(indentLevel);

        // 判断是否为合计行
        if (isTotalRow(label)) {
            row.setTotalRow(true);
        } else if (isSubtotalRow(label)) {
            row.setSubtotalRow(true);
        }

        // 解析数据单元格（从第二个开始）
        for (int i = 1; i < cells.size(); i++) {
            Element cellElement = cells.get(i);
            String cellText = cleanText(cellElement.text());
            TableCell cell = new TableCell(cellText);
            row.addCell(cell);
        }

        return row;
    }

    /**
     * 计算缩进级别
     */
    private int calculateIndentLevel(Element cellElement) {
        int level = 0;
        
        // 检查文本前的空格
        String text = cellElement.text();
        for (char c : text.toCharArray()) {
            if (c == ' ' || c == '\u00a0') { // 普通空格或不间断空格
                level++;
            } else {
                break;
            }
        }
        
        // 检查是否有嵌套列表或缩进样式
        if (!cellElement.select("ul, ol").isEmpty()) {
            level += 4;
        }
        
        // 每4个空格算一级缩进
        return Math.min(level / 4, 5); // 最多5级
    }

    /**
     * 判断是否为总计行
     */
    private boolean isTotalRow(String label) {
        if (label == null) return false;
        String lowerLabel = label.toLowerCase().trim();
        return lowerLabel.contains("total") || 
               lowerLabel.contains("consolidated") ||
               lowerLabel.contains("合计") ||
               lowerLabel.contains("总计") ||
               lowerLabel.contains("合并");
    }

    /**
     * 判断是否为小计行
     */
    private boolean isSubtotalRow(String label) {
        if (label == null) return false;
        String lowerLabel = label.toLowerCase().trim();
        return lowerLabel.contains("subtotal") ||
               lowerLabel.contains("小计");
    }

    /**
     * 清理文本
     */
    private String cleanText(String text) {
        if (text == null) {
            return "";
        }
        // 移除多余空白，但保留用于判断缩进的前导空格
        return text.replaceAll("[\\r\\n]+", " ").trim();
    }

    /**
     * 判断是否为有效的财务表格
     */
    private boolean isValidFinancialTable(FinancialTable table) {
        // 至少有2行2列
        if (table.getRows().size() < 2 || table.getHeaders().size() < 2) {
            return false;
        }
        
        // 检查是否有数值数据
        int numericCount = 0;
        for (TableRow row : table.getRows()) {
            for (TableCell cell : row.getCells()) {
                if (cell.isNumeric()) {
                    numericCount++;
                }
            }
        }
        
        // 至少有5个数值才认为是财务表格
        return numericCount >= 5;
    }

    /**
     * 推断币种和单位
     */
    private void inferCurrencyAndUnit(FinancialTable table) {
        String title = table.getTitle() != null ? table.getTitle().toLowerCase() : "";
        String allHeaders = String.join(" ", table.getHeaders()).toLowerCase();
        String combined = title + " " + allHeaders;

        // 推断币种
        if (combined.contains("rmb") || combined.contains("人民币") || combined.contains("元")) {
            table.setCurrency("RMB");
        } else if (combined.contains("us$") || combined.contains("us dollar") || combined.contains("美元")) {
            table.setCurrency("USD");
        } else if (combined.contains("$")) {
            table.setCurrency("USD");
        }

        // 推断单位
        if (combined.contains("million") || combined.contains("百万")) {
            table.setUnit("million");
        } else if (combined.contains("billion") || combined.contains("十亿")) {
            table.setUnit("billion");
        } else if (combined.contains("thousand") || combined.contains("千")) {
            table.setUnit("thousand");
        }
    }

}
