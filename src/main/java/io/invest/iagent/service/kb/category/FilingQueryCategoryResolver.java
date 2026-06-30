package io.invest.iagent.service.kb.category;

import org.apache.commons.lang3.StringUtils;

public class FilingQueryCategoryResolver {

    public String resolve(String query) {
        String q = StringUtils.defaultString(query).toLowerCase();
        if (containsAny(q, "risk", "风险", "不确定性")) return FilingContentCategory.OPERATING_RISKS.code();
        if (containsAny(q, "balance sheet", "cash flow", "income statement", "financial statement", "资产负债", "现金流", "利润表", "财务报表")) {
            return FilingContentCategory.FINANCIAL_STATEMENTS.code();
        }
        if (containsAny(q, "margin", "expense", "profit", "liquidity", "capital", "revenue", "cost", "利润", "费用", "收入", "成本", "毛利", "流动性")) {
            return FilingContentCategory.FINANCIAL_OPERATIONS.code();
        }
        if (containsAny(q, "business", "product", "customer", "delivery", "segment", "operation", "业务", "产品", "客户", "交付", "经营")) {
            return FilingContentCategory.BUSINESS_OPERATIONS.code();
        }
        if (containsAny(q, "legal", "governance", "board", "compensation", "lawsuit", "治理", "董事", "诉讼", "合规")) {
            return FilingContentCategory.GOVERNANCE_LEGAL.code();
        }
        if (containsAny(q, "strategy", "outlook", "competition", "market", "战略", "展望", "竞争", "市场")) {
            return FilingContentCategory.MARKET_STRATEGY.code();
        }
        if (containsAny(q, "esg", "sustainability", "employee", "human capital", "可持续", "员工", "人力资本")) {
            return FilingContentCategory.ESG_HUMAN_CAPITAL.code();
        }
        return null;
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (StringUtils.containsIgnoreCase(value, needle)) return true;
        }
        return false;
    }
}
