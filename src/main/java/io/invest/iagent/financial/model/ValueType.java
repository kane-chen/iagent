package io.invest.iagent.financial.model;

/**
 * 指标值类型。
 * <ul>
 *   <li>{@link #FLOW} 期间量（利润表/现金流量表）：累计口径可差分为单季；</li>
 *   <li>{@link #STOCK} 时点数（资产负债表）：不做差分；</li>
 *   <li>{@link #RATIO} 比率：查询时计算。</li>
 * </ul>
 */
public enum ValueType {
    FLOW,
    STOCK,
    RATIO
}
