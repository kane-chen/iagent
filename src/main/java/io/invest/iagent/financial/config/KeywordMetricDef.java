package io.invest.iagent.financial.config;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/**
 * 公司维度的关键字提取指标定义（keyword-metrics.yml 中某公司下的一条）。
 * 关键字支持多个，逗号分隔，可混用简体/繁体/英文；检索时还会自动并入
 * {@link KeywordDictEntry} 字典中同指标编码的三类语言同义词。
 */
@Data
public class KeywordMetricDef {

    /** 标准指标编码（须存在于 metric-catalog.yml） */
    private String code;

    /** 检索关键字，逗号分隔（如 "股份回购,回購股份,share repurchase"） */
    private String keywords;

    /** 给提取 LLM 的提示（披露位置/口径） */
    private String hint;

    /** 低于该置信度的提取结果不入库（0-100） */
    @JSONField(name = "min_confidence")
    private int minConfidence = 60;
}
