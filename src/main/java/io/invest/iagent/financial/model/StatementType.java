package io.invest.iagent.financial.model;

import lombok.Getter;

/**
 * 报表类型。
 */
@Getter
public enum StatementType {

    INCOME("income", "利润表"),
    BALANCE("balance", "资产负债表"),
    CASHFLOW("cashflow", "现金流量表"),
    DERIVED("derived", "派生指标");

    private final String code;
    private final String nameCn;

    StatementType(String code, String nameCn) {
        this.code = code;
        this.nameCn = nameCn;
    }

    public static StatementType fromCode(String code) {
        for (StatementType t : values()) {
            if (t.code.equalsIgnoreCase(code)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown statement type: " + code);
    }
}
