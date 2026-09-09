package io.invest.iagent.financial.config.prop;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.util.List;

/**
 * RAG 补充提取指标定义（rag-extra-metrics.yml 中的一条）。
 */
@Data
public class RagExtraMetric {

    /** 标准指标编码（须存在于 metric-catalog.yml） */
    private String code;

    /** 低于该置信度的提取结果不入库（0-100） */
    @JSONField(name = "min_confidence")
    private int minConfidence = 60;

    /** 检索同义词扩展 */
    private List<String> aliases;

    /** 给提取 LLM 的提示（披露位置/口径） */
    private String hint;
}
