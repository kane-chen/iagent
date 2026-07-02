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

        // 推断币种和单位 - 增强版,检查表格前后的文本
        inferCurrencyAndUnit(table, tableElement);

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

        // 检测是否为粗体文本（节标题特征）
        boolean isBold = detectBoldStyle(firstCell);
        row.setBold(isBold);

        // 先收集所有数据单元格的原始文本（从第二个开始），
        // 然后合并跨单元格的括号负数（如 "(138" + ")" 合并成 "(138)"）——
        // 部分 SEC HTML（BABA 的分部 EBITA 表）把 "(" 与 ")" 拆到相邻 <td>，
        // 直接构造 TableCell 会因括号不闭合而解析失败。
        List<String> cellTexts = new ArrayList<>(cells.size() - 1);
        for (int i = 1; i < cells.size(); i++) {
            cellTexts.add(cleanText(cells.get(i).text()));
        }
        mergeSplitParentheses(cellTexts);

        // 解析数据单元格
        for (String cellText : cellTexts) {
            row.addCell(new TableCell(cellText));
        }

        return row;
    }

    /**
     * 合并被 HTML 拆到相邻 <td> 的括号负数：
     * 当某个 cell 以 "(" 开头且没有以 ")" 结尾时，向后查找下一个非空 cell，
     * 若其以 ")" 开头，则把两者合并成一个完整的 "(N)"（原位置置空，保持列数）。
     *
     * <p>典型场景：BABA 2026Q1 报表 "Adjusted EBITA by segment" 里的负值行，
     * 每个 (数字) 被拆成 "(数字"、""、")" 三个 td。若不合并，TableCell 无法解析出数值，
     * 该整段分部行会被识别为"没有数据"，进而漏抓 ADJUSTED_EBITA。
     */
    private void mergeSplitParentheses(List<String> cellTexts) {
        for (int i = 0; i < cellTexts.size(); i++) {
            String t = cellTexts.get(i);
            if (t == null || t.isEmpty()) continue;
            if (!t.startsWith("(") || t.endsWith(")")) continue;

            for (int j = i + 1; j < cellTexts.size(); j++) {
                String next = cellTexts.get(j);
                if (next == null || next.isEmpty()) continue;
                if (next.startsWith(")")) {
                    // 合并：把闭括号（含后缀，如 ")%"）拼到 t，位置 j 置空以保持列数
                    cellTexts.set(i, t + next);
                    cellTexts.set(j, "");
                }
                // 无论是否合并，遇到第一个非空 cell 就停止扫描 —— 后面不再是配对候选
                break;
            }
        }
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
     * 检测单元格是否为粗体样式
     * 支持多种HTML粗体标记方式
     */
    private boolean detectBoldStyle(Element cellElement) {
        // 方法1: 检查是否有<b>, <strong>, <b>标签
        if (!cellElement.select("b, strong").isEmpty()) {
            return true;
        }

        // 方法2: 检查CSS样式是否包含font-weight: bold
        //   同时检查 cell 自身以及其内部的 <p>/<span>/<div> 等后代元素 ——
        //   微软 10-Q 用 <td><p><span style="font-weight:bold">Revenue</span></p></td>
        //   把粗体挂在最内层 span 上，只查 td.style 会漏掉。
        if (styleHasBold(cellElement.attr("style"))) {
            return true;
        }
        for (Element descendant : cellElement.select("*")) {
            if (styleHasBold(descendant.attr("style"))) {
                return true;
            }
        }

        // 方法3: 检查class属性是否暗示粗体
        String className = cellElement.className();
        if (className != null &&
            (className.toLowerCase().contains("bold") ||
             className.toLowerCase().contains("header"))) {
            return true;
        }

        return false;
    }

    /** style="font-weight:bold" / "font-weight: 700" 都视为粗体。 */
    private boolean styleHasBold(String style) {
        if (style == null || style.isEmpty()) return false;
        String s = style.toLowerCase();
        if (s.contains("font-weight")) {
            // 明示 bold 或 700+
            if (s.contains("bold")) return true;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("font-weight\\s*:\\s*(\\d{3})").matcher(s);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1)) >= 600;
                } catch (NumberFormatException ignored) {}
            }
        }
        return false;
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
     * 推断币种和单位(增强版)
     * 检查表格标题、表头以及周围的文本内容
     */
    private void inferCurrencyAndUnit(FinancialTable table, Element tableElement) {
        String title = table.getTitle() != null ? table.getTitle().toLowerCase() : "";
        String allHeaders = String.join(" ", table.getHeaders()).toLowerCase();
        String combined = title + " " + allHeaders;

        // 检查表格前后的兄弟元素,查找单位说明
        String surroundingText = extractSurroundingText(tableElement);
        if (surroundingText != null && !surroundingText.isEmpty()) {
            combined += " " + surroundingText.toLowerCase();
        }

        // 推断币种
        if (combined.contains("rmb") || combined.contains("人民币") || combined.contains("元")) {
            table.setCurrency("RMB");
        } else if (combined.contains("us$") || combined.contains("us dollar") || combined.contains("美元")) {
            table.setCurrency("USD");
        } else if (combined.contains("$")) {
            table.setCurrency("USD");
        }

        // 推断单位 - 优先级: million > billion > thousand
        if (combined.contains("million") || combined.contains("百万")) {
            table.setUnit("million");
        } else if (combined.contains("billion") || combined.contains("十亿")) {
            table.setUnit("billion");
        } else if (combined.contains("thousand") || combined.contains("千")) {
            table.setUnit("thousand");
        }
    }

    /**
     * 提取表格周围的文本(用于识别单位说明)
     * 增强版:不仅检查直接兄弟元素,还检查附近的段落
     */
    private String extractSurroundingText(Element tableElement) {
        StringBuilder text = new StringBuilder();
        
        // 方法1: 检查前一个兄弟元素
        Element prev = tableElement.previousElementSibling();
        if (prev != null) {
            String prevText = prev.text().trim();
            if (prevText.length() < 300 && isUnitDescription(prevText)) {
                text.append(prevText);
            }
        }
        
        // 方法2: 如果前一个元素不是单位说明,向前查找最多5个元素
        if (text.length() == 0) {
            Element current = tableElement.previousElementSibling();
            int lookbackCount = 0;
            while (current != null && lookbackCount < 5) {
                String elemText = current.text().trim();
                if (elemText.length() < 300 && isUnitDescription(elemText)) {
                    text.append(elemText);
                    break;
                }
                current = current.previousElementSibling();
                lookbackCount++;
            }
        }
        
        // 方法3: 检查后一个兄弟元素
        if (text.length() == 0) {
            Element next = tableElement.nextElementSibling();
            if (next != null) {
                String nextText = next.text().trim();
                if (nextText.length() < 300 && isUnitDescription(nextText)) {
                    text.append(nextText);
                }
            }
        }
        
        // 方法4: 检查父元素的文本内容(如果是页面标题或章节标题)
        if (text.length() == 0) {
            Element parent = tableElement.parent();
            if (parent != null) {
                Elements allElements = parent.getAllElements();
                int tableIndex = allElements.indexOf(tableElement);
                if (tableIndex > 0) {
                    for (int i = tableIndex - 1; i >= Math.max(0, tableIndex - 10); i--) {
                        Element elem = allElements.get(i);
                        if (elem.tagName().equals("p") || elem.tagName().equals("div")) {
                            String elemText = elem.text().trim();
                            if (elemText.length() < 300 && isUnitDescription(elemText)) {
                                text.append(elemText);
                                break;
                            }
                        }
                    }
                }
            }
        }
        
        return text.toString();
    }

    /**
     * 判断是否是单位说明文本
     */
    private boolean isUnitDescription(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("amounts in") || 
               lower.contains("in thousands") || 
               lower.contains("in millions") || 
               lower.contains("in billions") ||
               lower.contains("(amounts") ||
               lower.contains("单位:") ||
               lower.contains("金额单位");
    }

}
