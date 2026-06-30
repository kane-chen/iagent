package io.invest.iagent.service.extraction.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务分部模型
 */
@Data
public class Segment {

    private String segmentId;
    private String segmentName;
    private String segmentCode;
    private int level;
    private int sortOrder;

    @JsonIgnore
    @ToString.Exclude
    private Segment parent;

    private List<Segment> children;

    private List<SegmentMetric> metrics;

    public Segment() {
        this.children = new ArrayList<>();
        this.metrics = new ArrayList<>();
        this.level = 1;
    }

    public Segment(String segmentName, int level) {
        this();
        this.segmentName = segmentName;
        this.level = level;
    }

    /**
     * 添加子分部
     */
    public void addChild(Segment child) {
        child.setParent(this);
        this.children.add(child);
    }

    /**
     * 添加指标
     */
    public void addMetric(SegmentMetric metric) {
        metric.setSegment(this);
        this.metrics.add(metric);
    }

    /**
     * 根据指标编码获取指标值（不区分周期，返回第一个匹配的）
     */
    public SegmentMetric getMetric(String metricCode) {
        for (SegmentMetric metric : metrics) {
            if (metric.getMetricCode() != null && metric.getMetricCode().equalsIgnoreCase(metricCode)) {
                return metric;
            }
        }
        return null;
    }

    /**
     * 根据指标编码和周期获取指标值
     */
    public SegmentMetric getMetric(String metricCode, String period) {
        for (SegmentMetric metric : metrics) {
            if (metric.getMetricCode() != null && metric.getMetricCode().equalsIgnoreCase(metricCode)) {
                if (period == null || period.isEmpty() || period.equals(metric.getPeriod())) {
                    return metric;
                }
            }
        }
        return null;
    }

}
