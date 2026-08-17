package io.invest.iagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个标签过滤条件：tag_key {@link #operator} {@link #values}。
 * <p>EQ 时 values 只含一个值；IN 时 values 为候选集合（任选其一）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TagCondition {
    private String key;
    @Builder.Default
    private TagOperator operator = TagOperator.EQ;
    private List<String> values;

    public static TagCondition eq(String key, String value) {
        return TagCondition.builder()
                .key(key)
                .operator(TagOperator.EQ)
                .values(List.of(value))
                .build();
    }

    public static TagCondition in(String key, List<String> values) {
        return TagCondition.builder()
                .key(key)
                .operator(TagOperator.IN)
                .values(values == null ? List.of() : List.copyOf(values))
                .build();
    }
}
