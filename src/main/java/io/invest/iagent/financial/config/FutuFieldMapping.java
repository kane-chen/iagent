package io.invest.iagent.financial.config;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * futu 字段映射（futu-field-mapping.yml）：市场 -> 报表 -> 标准指标 -> futu field_id 列表。
 * 多个 field_id 表示求和（如港股 CapEx = 5071 + 5073）。
 */
@Data
public class FutuFieldMapping {

    private Map<String, MarketMapping> markets;

    @Data
    public static class MarketMapping {
        /** futu financial_type：10=美股单季+年报，11=港股/A股累计 */
        @JSONField(name = "financial_type")
        private int financialType;
        /** true 表示 API 返回年内累计值，入库前需差分出单季度 */
        private boolean cumulative;
        /** metricCode -> fieldId 列表 */
        private Map<String, List<Integer>> income;
        private Map<String, List<Integer>> balance;
        private Map<String, List<Integer>> cashflow;

        public Map<String, List<Integer>> statement(String statementKey) {
            return switch (statementKey) {
                case "income" -> income;
                case "balance" -> balance;
                case "cashflow" -> cashflow;
                default -> null;
            };
        }
    }

    public MarketMapping market(String market) {
        if (markets == null) {
            return null;
        }
        return markets.get(market == null ? "" : market.toLowerCase());
    }
}
