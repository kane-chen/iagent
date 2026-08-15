package io.invest.iagent.rag.retrieve.plugins;

import io.invest.iagent.rag.config.RagConfig;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.PluginErrorOrNone;
import io.invest.iagent.rag.retrieve.dto.PluginException;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.enums.QueryIntent;
import io.invest.iagent.service.filingrag.util.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * QUERY_UNDERSTAND：意图判断与查询改写
 */
@Slf4j
public class QueryUnderstandPlugin implements Plugin {

    private final LlmClient llmClient;
    private final RagConfig config;

    public QueryUnderstandPlugin(LlmClient llmClient, RagConfig config) {
        this.llmClient = llmClient;
        this.config = config;
    }

    @Override
    public List<EventType> activationEvents() {
        return Collections.singletonList(EventType.QUERY_UNDERSTAND);
    }

    @Override
    public PluginErrorOrNone onEvent(PipelineContext ctx, EventType eventType, ChatManage cm,
                                     Supplier<PluginErrorOrNone> next) throws PluginException {
        String query = cm.getQuery();
        if (StringUtils.isBlank(query)) {
            cm.getState().setIntent(QueryIntent.CLARIFICATION);
            cm.getState().setRewriteQuery(query);
            return next.get();
        }

        // 简单意图识别
        String q = query.toLowerCase();
        if (q.contains("你好") || q.contains("hi") || q.contains("hello")) {
            cm.getState().setIntent(QueryIntent.GREETING);
            cm.getState().setRewriteQuery(query);
            return next.get();
        }
        if (q.contains("网页") || q.contains("网络搜索") || q.contains("上网")) {
            cm.getState().setIntent(QueryIntent.WEB_SEARCH);
            cm.getState().setRewriteQuery(query);
            return next.get();
        }
        cm.getState().setIntent(QueryIntent.KB_SEARCH);

        // 查询改写
        String rewriteQuery = query;
        if (cm.getRequest().enableRewrite && llmClient != null) {
            rewriteQuery = rewriteQuery(query);
        }
        cm.getState().setRewriteQuery(StringUtils.defaultIfBlank(rewriteQuery, query));
        log.debug("Query rewrite: '{}' -> '{}'", query, cm.getState().getRewriteQuery());
        return next.get();
    }

    private String rewriteQuery(String query) {
        try {
            String systemPrompt = """
                    你是一个查询改写助手。请将用户的问题改写为更适合知识库检索的查询。
                    要求：
                    1. 消解指代词（如"它""该公司"替换为具体实体）
                    2. 提取关键搜索词
                    3. 保持原意，不要添加额外信息
                    只输出改写后的查询，不要解释。""";
            String result = llmClient.chat(systemPrompt, query);
            return StringUtils.trimToNull(result);
        } catch (Exception e) {
            log.warn("Query rewrite failed: {}", e.getMessage());
            return query;
        }
    }
}
