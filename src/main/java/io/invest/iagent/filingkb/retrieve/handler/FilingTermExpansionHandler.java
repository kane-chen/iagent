package io.invest.iagent.filingkb.retrieve.handler;

import io.invest.iagent.filingkb.term.FinancialTermDictionary;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.handler.Handler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 业务 handler #2：用财报术语词典对改写后的查询追加同义词后缀，
 * 提升 BM25 召回（向量检索也嵌入增强文本）。
 * <p>运行于 TagParse / PeriodNormalize 之后（@Order(30)），追加词不计入标签。
 */
@Service
@Slf4j
public class FilingTermExpansionHandler implements Handler {

    /** 追加同义词上限，避免查询过长 */
    private static final int MAX_APPENDED_TERMS = 15;

    @Override
    public List<EventType> activationEvents() {
        return Collections.singletonList(EventType.QUERY_UNDERSTAND);
    }

    @Override
    public void onEvent(PipelineContext ctx, EventType eventType, ChatManage cm) {
        if (!FilingHandlerSupport.isFilingDomain(cm)) return;

        String query = cm.getState().getRewriteQuery();
        if (StringUtils.isBlank(query)) return;

        Set<String> seeds = FinancialTermDictionary.extractSeeds(query);
        Set<String> expanded = FinancialTermDictionary.expand(seeds);

        // 去掉已在原 query 中的词
        String lowerQuery = query.toLowerCase();
        Set<String> toAppend = new LinkedHashSet<>();
        for (String term : expanded) {
            if (lowerQuery.contains(term.toLowerCase())) continue;
            if (term.length() < 2) continue;
            toAppend.add(term);
            if (toAppend.size() >= MAX_APPENDED_TERMS) break;
        }
        if (toAppend.isEmpty()) return;

        String enriched = query + " " + String.join(" ", toAppend);
        cm.getState().setRewriteQuery(enriched);
        log.debug("FilingKB term expansion appended {} terms", toAppend.size());
    }
}
