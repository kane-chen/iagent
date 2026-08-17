package io.invest.iagent.filingkb.retrieve.handler;

import io.invest.iagent.filingkb.retrieve.FilingTagKeys;
import io.invest.iagent.rag.model.TagCondition;
import io.invest.iagent.rag.model.TagFilter;
import io.invest.iagent.rag.retrieve.dto.ChatManage;

import java.util.Optional;

/**
 * filingkb handler 共用工具：domain 守卫、tagFilter 读写。
 */
final class FilingHandlerSupport {

    private FilingHandlerSupport() {}

    /** 仅当请求 domain="filing" 时执行 */
    static boolean isFilingDomain(ChatManage cm) {
        return cm != null && cm.getRequest() != null
                && FilingTagKeys.DOMAIN.equals(cm.getRequest().domain);
    }

    /**
     * 取 state.tagFilter（运行时优先），为空则回退请求中的预填 filter；
     * 若都为空则新建并写入 state。返回可变的 state.tagFilter。
     */
    static TagFilter mutableStateFilter(ChatManage cm) {
        if (cm.getState().tagFilter == null) {
            TagFilter prefill = cm.getRequest().tagFilter;
            cm.getState().tagFilter = new TagFilter();
            if (prefill != null && prefill.getConditions() != null) {
                prefill.getConditions().forEach(c -> cm.getState().tagFilter.getConditions().add(c));
            }
        }
        return cm.getState().tagFilter;
    }

    /** 查找指定 key 的已有条件（来自预填或前序 handler） */
    static Optional<TagCondition> findCondition(TagFilter filter, String key) {
        if (filter == null || filter.getConditions() == null) return Optional.empty();
        return filter.getConditions().stream()
                .filter(c -> key.equals(c.getKey()))
                .findFirst();
    }

    /** 若 key 尚不存在则添加条件 */
    static void addIfAbsent(TagFilter filter, TagCondition condition) {
        if (findCondition(filter, condition.getKey()).isEmpty()) {
            filter.add(condition);
        }
    }
}
