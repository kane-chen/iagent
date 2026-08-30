package io.invest.iagent.financial.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.util.List;

/**
 * 标准指标定义（metric-catalog.yml 中的一条）。
 * 指标目录跨公司统一，每家公司只是"有/无该指标"的差异，通过映射层适配。
 */
@Data
public class MetricDef {

    /** 稳定英文编码，入库 metric_code */
    private String code;

    /** 中文名 */
    @JSONField(name = "name_cn")
    private String nameCn;

    /** 英文名 */
    @JSONField(name = "name_en")
    private String nameEn;

    /** 所属报表 */
    private String statement;

    /** 父指标编码（分层展示），null 为顶层 */
    private String parent;

    /** 同层展示顺序 */
    @JSONField(name = "sort_order")
    private int sortOrder;

    /** 值类型：FLOW 期间量 / STOCK 时点数 / RATIO 比率 */
    @JSONField(name = "value_type")
    private String valueType;

    /** 单位：million 百万财报币种 / percent 百分比 */
    private String unit;

    /** 是否由其他指标计算得出 */
    private boolean derived;

    /** 查询别名（中英文） */
    private List<String> aliases;

    public StatementType statementType() {
        return StatementType.fromCode(statement);
    }

    public ValueType valueTypeEnum() {
        return ValueType.valueOf(valueType == null ? "FLOW" : valueType.toUpperCase());
    }
}
