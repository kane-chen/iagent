package io.invest.iagent.rag.retrieve.handler;

import io.invest.iagent.rag.chatting.Chatter;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.enums.QueryIntent;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * QUERY_UNDERSTAND：意图判断与查询改写
 */
@Slf4j
@Service
public class QueryUnderstandHandler implements Handler {

    @Autowired
    private Chatter chatter;

    @Override
    public String name() {
        return "QUERY_UNDERSTAND";
    }

    @Override
    public void handle(PipelineContext ctx, ChatManage cm) {
        String query = cm.getQuery();
        if (StringUtils.isBlank(query)) {
            cm.getState().setIntent(QueryIntent.CLARIFICATION);
            cm.getState().setRewriteQuery(query);
            return ;
        }

        // 简单意图识别
        String q = query.toLowerCase();
        if (q.contains("你好") || q.contains("hi") || q.contains("hello")) {
            cm.getState().setIntent(QueryIntent.GREETING);
            cm.getState().setRewriteQuery(query);
            return ;
        }
        if (q.contains("网页") || q.contains("网络搜索") || q.contains("上网")) {
            cm.getState().setIntent(QueryIntent.WEB_SEARCH);
            cm.getState().setRewriteQuery(query);
            return ;
        }
        cm.getState().setIntent(QueryIntent.KB_SEARCH);

        // 查询改写
        String rewriteQuery = query;
        //TODO
//        if (cm.getRequest().enableRewrite && chatter != null) {
//            rewriteQuery = rewriteQuery(query);
//        }
        cm.getState().setRewriteQuery(StringUtils.defaultIfBlank(rewriteQuery, query));
        log.debug("Query rewrite: '{}' -> '{}'", query, cm.getState().getRewriteQuery());
    }

    private String rewriteQuery(String query) {
        try {
            String systemPrompt = """
                    你是一个查询改写助手。请将用户的问题改写为更适合知识库检索的查询。
                    注意：
                    1、***保持原意，不要添加额外信息***。
                    2、***只输出结果***，不要输出思考过程。
                    """;
            String result = chatter.chat(systemPrompt, query);
            return StringUtils.trimToNull(result);
        } catch (Exception e) {
            log.warn("Query rewrite failed: {}", e.getMessage());
            return query;
        }
    }
}
