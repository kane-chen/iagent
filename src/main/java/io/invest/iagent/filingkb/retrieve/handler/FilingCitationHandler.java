package io.invest.iagent.filingkb.retrieve.handler;

import io.invest.iagent.filingkb.retrieve.FilingTagKeys;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.SearchResult;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.handler.Handler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 业务 handler #4：对最终 top-K 片段按标签格式化引用字符串 [Cn] TICKER YEAR PERIOD heading，
 * 写入 {@code metadata["citation"]}，随检索结果返回给 Agent。
 * <p>挂 {@code INTO_CHAT_MESSAGE}（位于 FILTER_TOP_K 之后）。
 * v1 不含页码（PDF reader 未保留页边界，后续增强）。
 */
@Service
@Slf4j
public class FilingCitationHandler implements Handler {

    @Override
    public List<EventType> activationEvents() {
        return Collections.singletonList(EventType.INTO_CHAT_MESSAGE);
    }

    @Override
    public void onEvent(PipelineContext ctx, EventType eventType, ChatManage cm) {
        if (!FilingHandlerSupport.isFilingDomain(cm))return;

        List<SearchResult> results = !cm.getState().getMergeResult().isEmpty()
                ? cm.getState().getMergeResult()
                : cm.getState().getSearchResult();
        if (results.isEmpty()) return;

        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            String citation = buildCitation(i + 1, r);
            r.getMetadata().put("citation", citation);
            // 扁平关键标签到 metadata，便于 Agent 直接取用
            if (r.getTags() != null) {
                putIfPresent(r, FilingTagKeys.TICKER);
                putIfPresent(r, FilingTagKeys.FISCAL_YEAR);
                putIfPresent(r, FilingTagKeys.FISCAL_PERIOD);
                putIfPresent(r, FilingTagKeys.FORM_TYPE);
                putIfPresent(r, FilingTagKeys.DOCUMENT_ID);
                putIfPresent(r, FilingTagKeys.HEADING);
            }
        }
    }

    private String buildCitation(int index, SearchResult r) {
        StringBuilder sb = new StringBuilder("[C").append(index).append("]");
        if (r.getTags() != null) {
            appendTag(sb, r.getTags().get(FilingTagKeys.TICKER));
            appendTag(sb, r.getTags().get(FilingTagKeys.FISCAL_YEAR));
            appendTag(sb, r.getTags().get(FilingTagKeys.FISCAL_PERIOD));
            String heading = r.getTags().get(FilingTagKeys.HEADING);
            if (StringUtils.isBlank(heading)) {
                heading = r.getContextHeader();
            }
            if (StringUtils.isNotBlank(heading)) {
                sb.append(' ').append(heading);
            }
        }
        return sb.toString();
    }

    private void appendTag(StringBuilder sb, String value) {
        if (StringUtils.isNotBlank(value)) sb.append(' ').append(value);
    }

    private void putIfPresent(SearchResult r, String key) {
        String v = r.getTags().get(key);
        if (StringUtils.isNotBlank(v)) r.getMetadata().put(key, v);
    }
}
