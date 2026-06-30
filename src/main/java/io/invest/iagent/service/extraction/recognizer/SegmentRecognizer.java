package io.invest.iagent.service.extraction.recognizer;

import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.FinancialTable;
import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.model.TableRow;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Stack;

/**
 * 业务线识别器
 * 从财务表格中识别业务分部并构建层级关系
 */
@Slf4j
public class SegmentRecognizer {

    @Getter
    private final CompanyConfig companyConfig;

    public SegmentRecognizer(CompanyConfig companyConfig) {
        this.companyConfig = companyConfig;
    }

    /**
     * 从表格中识别业务分部
     */
    public List<Segment> recognizeSegments(FinancialTable table) {

        log.info("Recognizing segments from table: {}", table.getTitle());

        // 方法1：基于公司配置的规则匹配（优先，最准确）
        List<Segment> configBased = recognizeByConfig(table);

        // 如果配置匹配有结果，优先使用配置结果，构建层级关系
        if (!configBased.isEmpty()) {
            // 构建父子层级关系
            buildHierarchy(configBased);
            log.info("Using config-based recognition: {} segments", configBased.size());
            return configBased;
        }

        // 方法2：基于缩进和表格结构的自动识别（回退方案）
        List<Segment> structureBased = recognizeByStructure(table);
        log.info("Using structure-based recognition: {} segments", structureBased.size());

        return structureBased;
    }

    /**
     * 基于公司配置识别业务分部
     */
    private List<Segment> recognizeByConfig(FinancialTable table) {
        List<Segment> segments = new ArrayList<>();
        
        if (companyConfig == null || companyConfig.getSegments() == null) {
            return segments;
        }

        for (CompanyConfig.SegmentConfig segmentConfig : companyConfig.getSegments()) {
            // 在表格行中查找匹配的行
            TableRow matchedRow = findMatchingRow(table, segmentConfig);
            
            if (matchedRow != null) {
                Segment segment = new Segment();
                segment.setSegmentCode(segmentConfig.getSegmentCode());
                segment.setSegmentName(segmentConfig.getSegmentName());
                segment.setLevel(segmentConfig.getLevel());
                segment.setSortOrder(table.getRows().indexOf(matchedRow));
                segments.add(segment);
                log.debug("Matched segment by config: {} -> {}", 
                        segmentConfig.getSegmentCode(), matchedRow.getLabel());
            }
        }

        return segments;
    }

    /**
     * 查找匹配的表格行
     */
    private TableRow findMatchingRow(FinancialTable table, CompanyConfig.SegmentConfig segmentConfig) {
        for (TableRow row : table.getRows()) {
            if (segmentConfig.matches(row.getLabel())) {
                return row;
            }
        }
        return null;
    }

    /**
     * 基于表格结构（缩进、层级）识别业务分部
     */
    private List<Segment> recognizeByStructure(FinancialTable table) {
        List<Segment> segments = new ArrayList<>();
        
        // 使用栈来构建层级关系
        Stack<Segment> stack = new Stack<>();
        
        for (TableRow row : table.getRows()) {
            String label = row.getLabel();
            if (label == null || label.trim().isEmpty()) {
                continue;
            }
            
            // 跳过合计行和小计行（它们不是业务分部）
            if (row.isTotalRow() || row.isSubtotalRow()) {
                continue;
            }
            
            // 跳过明显不是业务分部的行（包含数字过多的行）
            if (!isLikelySegmentName(label)) {
                continue;
            }
            
            int indentLevel = row.getIndentLevel();
            
            // 创建新的分部
            Segment segment = new Segment();
            segment.setSegmentName(label.trim());
            segment.setLevel(indentLevel + 1); // 缩进级别转层级
            segment.setSortOrder(table.getRows().indexOf(row));
            
            // 调整栈，找到父节点
            while (!stack.isEmpty() && stack.peek().getLevel() >= segment.getLevel()) {
                stack.pop();
            }
            
            if (!stack.isEmpty()) {
                stack.peek().addChild(segment);
            } else {
                segments.add(segment);
            }
            
            stack.push(segment);
        }
        
        return segments;
    }

    /**
     * 判断是否可能是业务分部名称
     */
    private boolean isLikelySegmentName(String label) {
        if (label == null || label.trim().isEmpty()) {
            return false;
        }

        String trimmed = label.trim();

        // 太短或太长都不太可能
        if (trimmed.length() < 2 || trimmed.length() > 100) {
            return false;
        }

        // 排除明显是财务指标的关键词
        String lower = trimmed.toLowerCase();
        String[] financialKeywords = {
            "revenue", "income", "profit", "loss", "expense", "cost", "margin",
            "earnings", "per share", "eps", "ads", "cash", "asset", "liability",
            "equity", "depreciation", "amortization", "tax", "interest",
            "ebit", "ebitda", "ebita", "adjusted", "non-gaap", "gaap",
            "收入", "利润", "亏损", "成本", "费用", "支出", "收益", "每股",
            "现金", "资产", "负债", "权益", "折旧", "摊销", "税", "利息",
            "经调整", "调整后", "非公认", "公认会计",
            "numerator", "denominator", "basic", "diluted", "share", "shares",
            "months", "year", "quarter", "0-3", "3-6", "6-12", "over",
            "accounts receivable", "accounts payable", "current", "deferred",
            "impairment", "goodwill", "intangible", "buyer protection",
            "less:", "add:", "for the", "for the three", "for the six",
            "减值", "商誉", "无形资产", "买家保障", "存款"
        };

        for (String keyword : financialKeywords) {
            if (lower.contains(keyword)) {
                return false;
            }
        }

        // 如果包含太多数字，可能不是分部名称
        int digitCount = 0;
        for (char c : trimmed.toCharArray()) {
            if (Character.isDigit(c)) {
                digitCount++;
            }
        }

        // 数字占比超过20%，不太可能是分部名称
        if (digitCount > 0 && (double) digitCount / trimmed.length() > 0.2) {
            return false;
        }

        return true;
    }

    /**
     * 构建层级关系 - 根据配置中的parentCode构建父子关系
     */
    private void buildHierarchy(List<Segment> segments) {
        if (companyConfig == null || companyConfig.getSegments() == null) {
            return;
        }

        // 首先构建所有segment的map，便于查找
        java.util.Map<String, Segment> segmentMap = new java.util.HashMap<>();
        for (Segment segment : segments) {
            segmentMap.put(segment.getSegmentCode(), segment);
        }

        // 按配置中的parentCode构建父子关系
        for (Segment segment : segments) {
            CompanyConfig.SegmentConfig config = findSegmentConfig(segment.getSegmentCode());
            if (config != null && config.getParentCode() != null) {
                Segment parent = segmentMap.get(config.getParentCode());
                if (parent != null) {
                    parent.addChild(segment);
                }
            }
        }
    }

    public boolean match(String label,String segmentCode){
        CompanyConfig.SegmentConfig config = findSegmentConfig(segmentCode);
        if(Objects.isNull(config)){
            return false ;
        }
        return config.matches(label) ;
    }

    /**
     * 查找分部配置
     */
    private CompanyConfig.SegmentConfig findSegmentConfig(String segmentCode) {
        if (companyConfig == null || companyConfig.getSegments() == null) {
            return null;
        }
        for (CompanyConfig.SegmentConfig config : companyConfig.getSegments()) {
            if (config.getSegmentCode().equals(segmentCode)) {
                return config;
            }
        }
        return null;
    }

    /**
     * 根据编码查找分部
     */
    private Segment findSegmentByCode(List<Segment> segments, String code) {
        for (Segment segment : segments) {
            if (code.equals(segment.getSegmentCode())) {
                return segment;
            }
            // 递归查找子分部
            Segment found = findSegmentByCode(segment.getChildren(), code);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

}
