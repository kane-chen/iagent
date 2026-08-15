package io.invest.iagent.rag.retrieve.dto;


import io.invest.iagent.rag.retrieve.model.EventBus;

/**
 * Pipeline运行时上下文
 * 对应 Go 中的 PipelineContext
 */
public class PipelineContext {
    // 事件总线（用于流式输出、进度推送）
    public final EventBus eventBus;
    
    // 消息ID
    public final String messageId;       // 当前生成的助手消息ID
    public final String userMessageId;   // 当前用户消息ID
    
    // 链路追踪ID
    public final String traceId;

    public PipelineContext(EventBus eventBus, String messageId, String userMessageId, String traceId) {
        this.eventBus = eventBus;
        this.messageId = messageId;
        this.userMessageId = userMessageId;
        this.traceId = traceId;
    }
}
