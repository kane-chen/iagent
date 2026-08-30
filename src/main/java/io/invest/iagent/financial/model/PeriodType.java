package io.invest.iagent.financial.model;

/**
 * 期间口径。
 * <ul>
 *   <li>{@link #SINGLE_Q} 单季度（美股原生 / 港股A股由累计值差分）；</li>
 *   <li>{@link #CUMULATIVE} 年内累计值（港股/A股 Q1/H1/9M 财报原始口径）；</li>
 *   <li>{@link #FY} 完整财年。</li>
 * </ul>
 */
public enum PeriodType {
    SINGLE_Q,
    CUMULATIVE,
    FY
}
