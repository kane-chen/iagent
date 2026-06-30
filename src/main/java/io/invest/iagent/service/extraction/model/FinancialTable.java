package io.invest.iagent.service.extraction.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 财务表格模型
 * 从财报中提取的标准化表格结构
 */
@Data
public class FinancialTable {

    private String tableId;
    private String title;
    private List<String> headers;
    private List<TableRow> rows;
    private List<String> footnotes;
    private String currency;
    private String unit;
    private String period;

    public FinancialTable() {
        this.headers = new ArrayList<>();
        this.rows = new ArrayList<>();
        this.footnotes = new ArrayList<>();
    }

}
