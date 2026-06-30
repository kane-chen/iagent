package io.invest.iagent.service.extraction.validator;

import io.invest.iagent.service.extraction.model.*;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 质量校验器
 * 对提取的财务数据进行质量校验
 */
@Slf4j
public class QualityValidator {

    // 允许的误差范围（百分比）
    private static final double DEFAULT_TOLERANCE = 0.05; // 5%

    /**
     * 执行完整的质量校验
     */
    public ValidationResult validate(List<Segment> segments) {
        ValidationResult result = new ValidationResult();
        
        log.info("Starting quality validation for {} segments", segments.size());
        
        // 1. 横向求和校验（子分部之和 = 父分部）
        validateHorizontalSum(segments, result);
        
        // 2. 纵向勾稽校验（收入 - 成本 - 费用 = 利润）
        validateVerticalArticulation(segments, result);
        
        // 3. 完整性校验
        validateCompleteness(segments, result);
        
        // 4. 置信度评估
        evaluateConfidence(segments, result);
        
        // 计算总分
        calculateOverallScore(result);
        
        log.info("Validation completed. Passed: {}, Score: {}, Errors: {}, Warnings: {}",
                result.isPassed(), result.getOverallScore(), 
                result.getErrorCount(), result.getWarningCount());
        
        return result;
    }

    /**
     * 横向求和校验
     * 检查子分部数据之和是否等于父分部数据
     */
    private void validateHorizontalSum(List<Segment> segments, ValidationResult result) {
        for (Segment segment : segments) {
            if (!segment.getChildren().isEmpty()) {
                validateSegmentSum(segment, result);
            }
            // 递归处理子分部
            validateHorizontalSum(segment.getChildren(), result);
        }
    }

    /**
     * 校验单个分部的子分部求和
     */
    private void validateSegmentSum(Segment parent, ValidationResult result) {
        List<Segment> children = parent.getChildren();
        if (children.isEmpty()) {
            return;
        }

        // 对每个指标进行校验
        for (SegmentMetric parentMetric : parent.getMetrics()) {
            String metricCode = parentMetric.getMetricCode();
            Double parentValue = parentMetric.getValue();
            
            if (parentValue == null) {
                continue;
            }

            // 计算子分部的和
            double childrenSum = 0;
            boolean hasChildrenData = false;
            
            for (Segment child : children) {
                SegmentMetric childMetric = child.getMetric(metricCode);
                if (childMetric != null && childMetric.getValue() != null) {
                    childrenSum += childMetric.getValue();
                    hasChildrenData = true;
                }
            }

            if (!hasChildrenData) {
                continue;
            }

            // 检查是否在误差范围内
            double diff = Math.abs(parentValue - childrenSum);
            double tolerance = Math.abs(parentValue) * DEFAULT_TOLERANCE;
            
            if (diff > tolerance) {
                ValidationResult.ValidationIssue issue = new ValidationResult.ValidationIssue();
                issue.setRuleCode("HORIZONTAL_SUM_MISMATCH");
                issue.setMessage(String.format("分部[%s]的%s指标：父分部值%.2f，子分部求和%.2f，差异%.2f（%.1f%%）",
                        parent.getSegmentName(), metricCode, parentValue, childrenSum, 
                        diff, parentValue != 0 ? (diff / parentValue * 100) : 0));
                issue.setSeverity(ValidationResult.Severity.WARNING);
                issue.setSegmentName(parent.getSegmentName());
                issue.setMetricCode(metricCode);
                issue.setExpectedValue(parentValue);
                issue.setActualValue(childrenSum);
                result.addIssue(issue);
                log.warn(issue.getMessage());
            }
        }
    }

    /**
     * 纵向勾稽校验
     * 检查收入 - 成本 - 费用 = 利润的勾稽关系
     */
    private void validateVerticalArticulation(List<Segment> segments, ValidationResult result) {
        for (Segment segment : segments) {
            validateSegmentArticulation(segment, result);
            // 递归处理子分部
            validateVerticalArticulation(segment.getChildren(), result);
        }
    }

    /**
     * 校验单个分部的勾稽关系
     */
    private void validateSegmentArticulation(Segment segment, ValidationResult result) {
        SegmentMetric revenue = segment.getMetric("REVENUE");
        SegmentMetric cost = segment.getMetric("COST_OF_REVENUE");
        SegmentMetric operatingExpenses = segment.getMetric("OPERATING_EXPENSES");
        SegmentMetric operatingIncome = segment.getMetric("OPERATING_INCOME");

        // 如果有收入、成本、经营利润，可以校验毛利
        if (revenue != null && cost != null && operatingIncome != null) {
            if (revenue.getValue() != null && cost.getValue() != null && operatingIncome.getValue() != null) {
                double expectedOperatingIncome = revenue.getValue() - cost.getValue();
                
                // 如果还有运营费用，也要减去
                if (operatingExpenses != null && operatingExpenses.getValue() != null) {
                    expectedOperatingIncome -= operatingExpenses.getValue();
                }
                
                double actual = operatingIncome.getValue();
                double diff = Math.abs(actual - expectedOperatingIncome);
                double tolerance = Math.abs(actual) * DEFAULT_TOLERANCE;
                
                if (diff > tolerance) {
                    ValidationResult.ValidationIssue issue = new ValidationResult.ValidationIssue();
                    issue.setRuleCode("VERTICAL_ARTICULATION_MISMATCH");
                    issue.setMessage(String.format("分部[%s]经营利润勾稽校验：计算值%.2f，实际值%.2f，差异%.2f",
                            segment.getSegmentName(), expectedOperatingIncome, actual, diff));
                    issue.setSeverity(ValidationResult.Severity.WARNING);
                    issue.setSegmentName(segment.getSegmentName());
                    issue.setMetricCode("OPERATING_INCOME");
                    issue.setExpectedValue(expectedOperatingIncome);
                    issue.setActualValue(actual);
                    result.addIssue(issue);
                    log.warn(issue.getMessage());
                }
            }
        }
    }

    /**
     * 完整性校验
     * 检查关键指标是否缺失
     */
    private void validateCompleteness(List<Segment> segments, ValidationResult result) {
        String[] criticalMetrics = {"REVENUE", "OPERATING_INCOME"};
        
        for (Segment segment : segments) {
            // 只检查一级分部
            if (segment.getLevel() == 1 || segment.getParent() == null) {
                for (String metricCode : criticalMetrics) {
                    if (segment.getMetric(metricCode) == null) {
                        ValidationResult.ValidationIssue issue = new ValidationResult.ValidationIssue();
                        issue.setRuleCode("MISSING_CRITICAL_METRIC");
                        issue.setMessage(String.format("分部[%s]缺少关键指标：%s", 
                                segment.getSegmentName(), metricCode));
                        issue.setSeverity(ValidationResult.Severity.INFO);
                        issue.setSegmentName(segment.getSegmentName());
                        issue.setMetricCode(metricCode);
                        result.addIssue(issue);
                        log.info(issue.getMessage());
                    }
                }
            }
            // 递归处理子分部
            validateCompleteness(segment.getChildren(), result);
        }
    }

    /**
     * 置信度评估
     */
    private void evaluateConfidence(List<Segment> segments, ValidationResult result) {
        int totalMetrics = 0;
        int totalConfidence = 0;
        int lowConfidenceCount = 0;

        for (Segment segment : segments) {
            for (SegmentMetric metric : segment.getMetrics()) {
                if (metric.getConfidenceScore() != null) {
                    totalMetrics++;
                    totalConfidence += metric.getConfidenceScore();
                    if (metric.getConfidenceScore() < 60) {
                        lowConfidenceCount++;
                    }
                }
            }
            // 递归处理子分部
            evaluateConfidence(segment.getChildren(), result);
        }

        if (totalMetrics > 0) {
            double avgConfidence = (double) totalConfidence / totalMetrics;
            log.info("Average confidence score: {:.1f}%", avgConfidence);
            
            if (lowConfidenceCount > 0) {
                ValidationResult.ValidationIssue issue = new ValidationResult.ValidationIssue();
                issue.setRuleCode("LOW_CONFIDENCE_METRICS");
                issue.setMessage(String.format("有%d个指标置信度低于60分", lowConfidenceCount));
                issue.setSeverity(ValidationResult.Severity.INFO);
                result.addIssue(issue);
            }
        }
    }

    /**
     * 计算总体评分
     */
    private void calculateOverallScore(ValidationResult result) {
        int score = 100;
        
        // 每个错误扣20分
        score -= result.getErrorCount() * 20;
        
        // 每个警告扣5分
        score -= result.getWarningCount() * 5;
        
        result.setOverallScore(Math.max(0, Math.min(100, score)));
    }

}
