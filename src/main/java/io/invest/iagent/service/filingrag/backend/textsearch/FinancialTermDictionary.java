package io.invest.iagent.service.filingrag.backend.textsearch;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 财报术语同义词词典。
 * 将常见财报问题中的术语映射为一组同义词/相关词，用于查询关键词扩展。
 * 每组中第一个词为主词条，其余为同义词（包含中英文及繁简体）。
 */
public final class FinancialTermDictionary {

    private FinancialTermDictionary() {}

    /**
     * 财报术语同义词组。每组包含主词条和其同义词。
     * 覆盖收入、利润、增长、现金流、资产负债、费用、业务分部、研发营销、指引展望、回购分红、风险、用户指标、市场竞争等主题。
     */
    private static final String[][] TERM_GROUPS = {
            // 收入类
            {"收入", "营收", "营业收入", "revenue", "sales", "revenues", "营业额", "收益", "turnover"},
            {"净收入", "net revenue", "net sales"},
            // 利润类
            {"利润", "profit", "收益", "盈利", "gain"},
            {"净利润", "净利", "净收益", "net income", "net profit", "纯利", "纯利润", "淨利潤"},
            {"毛利", "gross profit", "毛利润"},
            {"毛利率", "gross margin", "gross profit margin"},
            {"营业利润", "经营利润", "经营亏损","operating profit", "operating income", "营业收益","营业亏损"},
            {"EBITDA", "ebitda"},
            {"EBITA", "ebita"},
            {"EBIT", "ebit", "息税前利润"},
            // 增长/下降类
            {"增长", "增加", "上升", "增长", "growth", "increase", "grow", "提升", "上涨"},
            {"下滑", "下降", "减少", "下跌", "降低", "decline", "decrease", "drop", "fall", "reduce"},
            {"同比", "去年同期", "year-over-year", "yoy", "compared to last year", "较上年"},
            {"环比", "上季度", "quarter-over-quarter", "qoq", "较上季"},
            // 现金流类
            {"现金流", "现金流量", "cash flow", "cash flows", "現金流"},
            {"经营现金流", "经营性现金流", "operating cash flow", "经营活动现金流"},
            {"自由现金流", "free cash flow", "fcf"},
            // 资产负债类
            {"资产", "asset", "assets", "資產"},
            {"负债", "liability", "liabilities", "debt", "債務", "負債"},
            {"资产负债表", "balance sheet"},
            {"权益", "equity", "股东权益", "shareholders equity", "所有者权益"},
            {"负债率", "负债比率", "debt ratio", "leverage", "杠杆率", "gearing ratio"},
            {"现金及等价物", "现金等价物", "cash and equivalents", "cash and cash equivalents"},
            // 费用类
            {"研发费用", "研发", "r&d", "research and development", "研发开支", "研发投入"},
            {"营销费用", "销售费用", "marketing expense", "selling expense", "营销开支", "销售及营销费用"},
            {"管理费用", "管理开支", "administrative expense", "g&a", "general and administrative"},
            {"运营费用", "营业费用", "operating expense", "opex"},
            {"资本支出", "capital expenditure", "capex", "资本开支"},
            {"所得税", "income tax", "tax expense"},
            // 业务分部
            {"分部", "部门", "业务分部", "segment", "segments", "分业务", "业务板块", "业务线"},
            {"业务", "business", "业务线", "业务板块"},
            // 指引展望
            {"指引", "展望", "guidance", "outlook", "forecast", "预期", "预计", "未来展望"},
            // 回购分红
            {"回购", "repurchase", "buyback", "share repurchase", "股份回购", "回購"},
            {"分红", "股息", "股利", "dividend", "dividends", "派息", "分红派息"},
            // 风险
            {"风险", "不确定性", "risk", "risks", "risk factors", "風險"},
            {"挑战", "challenge", "challenges", "困境"},
            // 用户/客户指标
            {"用户", "客户", "user", "users", "customer", "customers", "活跃用户", "月活"},
            {"MAU", "mau", "月活跃用户", "monthly active users"},
            {"DAU", "dau", "日活跃用户", "daily active users"},
            {"ARPU", "arpu", "每用户平均收入", "average revenue per user"},
            {"GMV", "gmv", "商品交易总额", "成交总额"},
            {"订阅用户", "付费用户", "subscriber", "subscribers", "paying users", "付费会员"},
            // 市场竞争
            {"市场份额", "市占率", "market share", "marketshare", "市场占有率"},
            {"竞争", "竞争对手", "competitor", "competitors", "competition", "competitive"},
            // 成本类
            {"成本", "cost", "costs", "cost of revenue", "营业成本", "收入成本"},
            {"毛利率", "gross margin"},
            // 运营指标
            {"利润率", "profit margin", "margin", "净利率", "net margin"},
            {"经营利润率", "operating margin", "营业利润率"},
            // 员工
            {"员工", "雇员", "employee", "employees", "staff", "headcount", "人员"},
            {"裁员", "layoff", "layoffs", "headcount reduction", "人员优化"},
    };

    /**
     * 主词条到所有同义词（包含主词条自身）的映射，用于快速查找扩展。
     */
    private static final Map<String, Set<String>> EXPANSION_MAP;

    static {
        EXPANSION_MAP = new LinkedHashMap<>();
        for (String[] group : TERM_GROUPS) {
            Set<String> terms = new LinkedHashSet<>(Arrays.asList(group));
            for (String term : group) {
                EXPANSION_MAP.put(term.toLowerCase(), terms);
            }
        }
    }

    /**
     * 根据输入的关键词列表，通过词典扩展为包含同义词的关键词集合。
     *
     * @param keywords 原始关键词
     * @return 扩展后的关键词集合（包含原词和同义词）
     */
    public static Set<String> expand(Iterable<String> keywords) {
        Set<String> result = new LinkedHashSet<>();
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            String trimmed = kw.trim();
            result.add(trimmed);
            String lower = trimmed.toLowerCase();
            Set<String> synonyms = EXPANSION_MAP.get(lower);
            if (synonyms != null) {
                result.addAll(synonyms);
            }
        }
        return result;
    }

    /**
     * 获取所有主词条列表（用于LLM prompt参考）。
     */
    public static List<String> getPrimaryTerms() {
        return Arrays.stream(TERM_GROUPS).map(g -> g[0]).toList();
    }

    /**
     * 将术语词典格式化为提示词文本（主词条：同义词1、同义词2...），供LLM参考标准财报术语。
     */
    public static String formatTermGroupsForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (String[] group : TERM_GROUPS) {
            if (group.length == 0) continue;
            sb.append(group[0]);
            if (group.length > 1) {
                sb.append("（");
                for (int i = 1; i < group.length; i++) {
                    if (i > 1) sb.append("、");
                    sb.append(group[i]);
                }
                sb.append("）");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
