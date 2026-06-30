package io.invest.iagent.service.kb.category;

import io.invest.iagent.service.kb.model.FilingChunkingContext;
import org.apache.commons.lang3.StringUtils;

public class RuleBasedFilingContentCategoryClassifier implements FilingContentCategoryClassifier {

    @Override
    public FilingCategoryClassification classify(FilingChunkingContext context, String chunkText, String sectionTitle) {
        String title = StringUtils.defaultString(sectionTitle).toLowerCase();
        String text = StringUtils.defaultString(chunkText).toLowerCase();
        String source = StringUtils.defaultString(context.getSourceFile().getFileName().toString()).toLowerCase();
        String combined = title + " " + text + " " + source;

        if (containsAny(combined, "risk factors", "risk factor", "风险因素", "主要风险", "经营风险")) {
            return FilingCategoryClassification.of(FilingContentCategory.OPERATING_RISKS, 0.95, "section_title");
        }
        if (containsAny(combined, "balance sheet", "balance sheets", "statements of operations", "income statement",
                "cash flow", "cash flows", "stockholders' equity", "shareholders' equity",
                "consolidated financial statements", "notes to consolidated", "audit report",
                "assets", "liabilities", "revenues", "net income", "财务报表", "资产负债表", "现金流量表", "利润表")) {
            return FilingCategoryClassification.of(FilingContentCategory.FINANCIAL_STATEMENTS, 0.90, "keyword");
        }
        if (containsAny(combined, "management discussion", "liquidity", "capital resources", "gross margin",
                "operating margin", "operating expenses", "research and development", "selling general and administrative",
                "debt", "cash and cash equivalents", "capital expenditure", "working capital", "tax expense",
                "经营情况讨论与分析", "管理层讨论", "流动性", "资本资源", "毛利", "费用", "营运资金")) {
            return FilingCategoryClassification.of(FilingContentCategory.FINANCIAL_OPERATIONS, 0.86, "keyword");
        }
        if (containsAny(combined, "business", "products", "services", "customers", "sales channels", "deliveries",
                "production", "segments", "research and development", "employees", "业务", "产品", "服务", "客户", "交付", "产能", "分部")) {
            return FilingCategoryClassification.of(FilingContentCategory.BUSINESS_OPERATIONS, 0.82, "keyword");
        }
        if (containsAny(combined, "legal proceedings", "governance", "board of directors", "executive compensation",
                "shareholder", "regulatory", "compliance", "lawsuit", "诉讼", "董事会", "治理", "股东", "合规", "监管")) {
            return FilingCategoryClassification.of(FilingContentCategory.GOVERNANCE_LEGAL, 0.80, "keyword");
        }
        if (containsAny(combined, "strategy", "strategic", "outlook", "market opportunity", "competition", "competitive",
                "growth plan", "战略", "展望", "竞争", "市场机会", "增长计划")) {
            return FilingCategoryClassification.of(FilingContentCategory.MARKET_STRATEGY, 0.78, "keyword");
        }
        if (containsAny(combined, "sustainability", "esg", "human capital", "workforce", "diversity", "safety", "environmental",
                "可持续", "人力资本", "员工安全", "多元化", "环境")) {
            return FilingCategoryClassification.of(FilingContentCategory.ESG_HUMAN_CAPITAL, 0.76, "keyword");
        }
        return FilingCategoryClassification.of(FilingContentCategory.OTHER, 0.50, "fallback");
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (StringUtils.containsIgnoreCase(value, needle)) {
                return true;
            }
        }
        return false;
    }
}
