package io.invest.iagent.service.extraction.model;

import lombok.Data;

/**
 * 表格单元格模型
 */
@Data
public class TableCell {

    private String text;
    private Double numericValue;
    private boolean isNegative;
    private boolean isParentheses; // 是否用括号表示负数
    private String unit;

    public TableCell() {
    }

    public TableCell(String text) {
        this.text = text;
        parseNumericValue();
    }

    public void setText(String text) {
        this.text = text;
        parseNumericValue();
    }

    /**
     * 解析文本中的数值
     */
    private void parseNumericValue() {
        if (text == null || text.trim().isEmpty()) {
            this.numericValue = null;
            return;
        }

        String cleanText = text.trim();

        // 检查是否用括号表示负数
        if (cleanText.startsWith("(") && cleanText.endsWith(")")) {
            this.isParentheses = true;
            this.isNegative = true;
            cleanText = cleanText.substring(1, cleanText.length() - 1);
        }

        // 检查是否有负号
        if (cleanText.startsWith("-")) {
            this.isNegative = true;
            cleanText = cleanText.substring(1);
        }

        // 移除货币符号和逗号
        cleanText = cleanText.replace("$", "")
                .replace("¥", "")
                .replace("RMB", "")
                .replace("US$", "")
                .replace(",", "")
                .replace("%", "")
                .trim();

        try {
            double value = Double.parseDouble(cleanText);
            this.numericValue = isNegative ? -value : value;
        } catch (NumberFormatException e) {
            this.numericValue = null;
        }
    }

    /**
     * 是否为数值单元格
     */
    public boolean isNumeric() {
        return numericValue != null;
    }
}
