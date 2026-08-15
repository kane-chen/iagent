package io.invest.iagent.rag.retrieve.dto;

// 插件错误（对应PluginError）
public class PluginException extends Exception {
    public final String errorType;
    public final String description;
    public PluginException(String errorType, String description, Throwable cause) {
        super(description, cause);
        this.errorType = errorType;
        this.description = description;
    }
    public static final PluginException ERR_SEARCH_NOTHING =
            new PluginException("search_nothing", "No relevant content found", null);
    public static final PluginException ERR_SEARCH =
            new PluginException("search_failed", "Failed to search knowledge base", null);
    public static final PluginException ERR_RERANK =
            new PluginException("rerank_failed", "Reranking failed", null);
    public static final PluginException ERR_GET_RERANK_MODEL =
            new PluginException("get_rerank_model_failed", "Failed to get rerank model", null);
    public static final PluginException ERR_GET_CHAT_MODEL =
            new PluginException("get_chat_model_failed", "Failed to get chat model", null);
    public static final PluginException ERR_TEMPLATE_PARSE =
            new PluginException("template_parse_failed", "Failed to parse context template", null);
    public static final PluginException ERR_TEMPLATE_EXECUTE =
            new PluginException("template_execution_failed", "Failed to generate search content", null);
    public static final PluginException ERR_MODEL_CALL =
            new PluginException("model_call_failed", "Failed to call model", null);
    public static final PluginException ERR_GET_HISTORY =
            new PluginException("get_history_failed", "Failed to get conversation history", null);
}