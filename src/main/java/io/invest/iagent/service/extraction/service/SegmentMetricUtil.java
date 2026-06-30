package io.invest.iagent.service.extraction.service;

import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.model.SegmentMetric;
import io.invest.iagent.service.extraction.model.SegmentMetricDTO;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class SegmentMetricUtil {

    public static List<Segment> merge(List<Segment> segments){
        if(CollectionUtils.isEmpty(segments)){
            return List.of() ;
        }
        return segments.stream()
                .collect(Collectors.groupingBy(Segment::getSegmentCode))
                .values()
                .stream().map(SegmentMetricUtil::doMerge)
                .toList();
    }

    private static Segment doMerge(List<Segment> segments){
        if(CollectionUtils.isEmpty(segments)){
            return null ;
        }
        // metrics
        List<SegmentMetric> metrics = segments.stream()
                .map(Segment::getMetrics)
                .flatMap(List::stream).toList() ;
        // children
        List<Segment> children = segments.stream()
                .map(Segment::getChildren)
                .flatMap(List::stream).toList() ;
        children = merge( children) ;
        // wrap
        Segment segment = segments.get(0);
        segment.setMetrics(metrics);
        segment.setChildren(children);
        return segment ;
    }

    /**
     * 将Segment树状结构转换为一层的SegmentMetricDTO列表，并按规则排序
     * <p>排序规则：</p>
     * <ol>
     *   <li>先按业务分组（按sortOrder排序）</li>
     *   <li>每个业务组内，按指标维度遍历（按metricCode字典序）</li>
     *   <li>每个指标维度内，对该业务及其子业务进行深度优先遍历</li>
     * </ol>
     * <p>举例：A有A1、A2子业务，B有B1、B2子业务，都有成本和利润指标</p>
     * <p>结果顺序：A成本、A1成本、A2成本、A利润、A1利润、A2利润、B成本、B1成本、B2成本、B利润、B1利润、B2利润</p>
     *
     * @param segments 业务分部列表（根节点）
     * @return 扁平化并排序后的指标DTO列表
     */
    public static List<SegmentMetricDTO> flattenAndSort(List<Segment> segments) {
        if (CollectionUtils.isEmpty(segments)) {
            return new ArrayList<>();
        }

        List<SegmentMetricDTO> result = new ArrayList<>();
        // filter
        segments = segments.stream().filter(Objects::nonNull)
                .filter(t->t.getLevel()==1).toList() ;
        // 排序
        segments = segments.stream()
                .sorted(Comparator.comparingInt(Segment::getSortOrder)).toList();
        // 步骤1: 先收集所有唯一的指标编码（按指标名排序）
        List<String> metricCodes = collectAllMetricCodes(segments);

        // 步骤2: 先对顶级业务按sortOrder排序
        List<Segment> sortedSegments = segments.stream()
                .sorted(Comparator.comparingInt(Segment::getSortOrder))
                .toList();

        // 步骤3: 逐个业务处理：对每个业务，按指标维度深度优先遍历该业务树
        for (Segment segment : sortedSegments) {
            for (String metricCode : metricCodes) {
                depthFirstTraverseBusinessTreeByMetric(segment, metricCode, result);
            }
        }

        return result;
    }

    /**
     * 对指定业务树进行深度优先遍历，只收集指定指标
     */
    private static void depthFirstTraverseBusinessTreeByMetric(Segment segment, String metricCode, List<SegmentMetricDTO> result) {
        if (segment == null) {
            return;
        }

        // 处理当前业务的指定指标（遍历所有周期的该指标）
        for (SegmentMetric metric : segment.getMetrics()) {
            if (metric.getMetricCode() != null && metric.getMetricCode().equalsIgnoreCase(metricCode)) {
                result.add(convertToDTO(segment, metric));
            }
        }

        // 递归处理子业务（深度优先）
        if (!CollectionUtils.isEmpty(segment.getChildren())) {
            // 子业务按sortOrder排序
            List<Segment> sortedChildren = segment.getChildren().stream()
                    .sorted(Comparator.comparingInt(Segment::getSortOrder))
                    .toList();
            for (Segment child : sortedChildren) {
                depthFirstTraverseBusinessTreeByMetric(child, metricCode, result);
            }
        }
    }

    /**
     * 收集所有业务中出现的唯一指标编码，并按字典序排序
     */
    private static List<String> collectAllMetricCodes(List<Segment> segments) {
        List<String> metricCodes = new ArrayList<>();
        collectMetricCodesRecursive(segments, metricCodes);
        return metricCodes.stream()
                .distinct()
                .sorted() // 按指标编码字典序排序，可根据需要调整排序规则
                .toList();
    }

    /**
     * 递归收集指标编码
     */
    private static void collectMetricCodesRecursive(List<Segment> segments, List<String> metricCodes) {
        if (CollectionUtils.isEmpty(segments)) {
            return;
        }
        for (Segment segment : segments) {
            if (!CollectionUtils.isEmpty(segment.getMetrics())) {
                for (SegmentMetric metric : segment.getMetrics()) {
                    if (metric.getMetricCode() != null && !metricCodes.contains(metric.getMetricCode())) {
                        metricCodes.add(metric.getMetricCode());
                    }
                }
            }
            if (!CollectionUtils.isEmpty(segment.getChildren())) {
                collectMetricCodesRecursive(segment.getChildren(), metricCodes);
            }
        }
    }

    /**
     * 将SegmentMetric转换为SegmentMetricDTO
     */
    private static SegmentMetricDTO convertToDTO(Segment segment, SegmentMetric metric) {
        String parentSegmentCode = null;
        if (segment.getParent() != null) {
            parentSegmentCode = segment.getParent().getSegmentCode();
        }
        return SegmentMetricDTO.builder()
                .segmentCode(segment.getSegmentCode())
                .segmentName(segment.getSegmentName())
                .level(segment.getLevel())
                .parentSegmentCode(parentSegmentCode)
                .metricCode(metric.getMetricCode())
                .metricName(metric.getMetricName())
                .value(metric.getValue())
                .yoyGrowth(metric.getYoyGrowth())
                .confidenceScore(metric.getConfidenceScore())
                .sourceType(metric.getSourceType())
                .sourceLocation(metric.getSourceLocation())
                .currency(metric.getCurrency())
                .unit(metric.getUnit())
                .period(metric.getPeriod())
                .build();
    }

}
