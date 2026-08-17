package io.invest.iagent.rag.model;

/**
 * 标签过滤操作符：EQ 单值等值，IN 多值任选其一。
 * 多个 {@link TagCondition} 之间为 AND。
 */
public enum TagOperator {
    EQ,
    IN
}
