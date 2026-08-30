package io.invest.iagent.financial.model;

import lombok.Builder;
import lombok.Data;

/**
 * fin_segment 一行：公司分部目录（公司特定维度）。
 */
@Data
@Builder
public class SegmentDO {

    private String ticker;
    private String segmentCode;
    private String segmentName;
    /** 父分部编码，null 为一级分部 */
    private String parentCode;
    private int level;
    private int sortOrder;
}
