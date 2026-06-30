package io.invest.iagent.service.extraction.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格行模型
 */
@Data
public class TableRow {

    private String label;
    private List<TableCell> cells;
    private int indentLevel;
    private boolean isTotalRow;
    private boolean isSubtotalRow;

    public TableRow() {
        this.cells = new ArrayList<>();
        this.indentLevel = 0;
    }

    /**
     * 获取指定列的数值
     */
    public Double getNumericValue(int colIndex) {
        if (colIndex < 0 || colIndex >= cells.size()) {
            return null;
        }
        TableCell cell = cells.get(colIndex);
        return cell != null ? cell.getNumericValue() : null;
    }

    /**
     * 获取指定列的原始文本
     */
    public String getCellText(int colIndex) {
        if (colIndex < 0 || colIndex >= cells.size()) {
            return null;
        }
        TableCell cell = cells.get(colIndex);
        return cell != null ? cell.getText() : null;
    }

    /**
     * 添加单元格
     */
    public void addCell(TableCell cell) {
        this.cells.add(cell);
    }
}