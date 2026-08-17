package io.invest.iagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签过滤条件集合，条件之间为 AND。
 * <p>空集合（null 或 {@link #isEmpty()}）表示不做标签过滤。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TagFilter {
    @Builder.Default
    private List<TagCondition> conditions = new ArrayList<>();

    public static TagFilter of(TagCondition... conds) {
        TagFilter f = new TagFilter();
        if (conds != null) {
            for (TagCondition c : conds) {
                if (c != null) f.conditions.add(c);
            }
        }
        return f;
    }

    public boolean isEmpty() {
        return conditions == null || conditions.isEmpty();
    }

    public TagFilter add(TagCondition c) {
        if (c != null) {
            if (conditions == null) conditions = new ArrayList<>();
            conditions.add(c);
        }
        return this;
    }
}
