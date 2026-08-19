package io.invest.iagent.rag.retrieve.dto;


import lombok.Data;

/**
 * Pipeline运行时上下文
 * 对应 Go 中的 PipelineContext
 */
@Data
public class PipelineContext {

    // 消息ID
    public final String messageId;       // 当前生成的助手消息ID
    public final String userMessageId;   // 当前用户消息ID
    
    // 链路追踪ID
    public final String traceId;

    public PipelineContext(String messageId, String userMessageId, String traceId) {
        this.messageId = messageId;
        this.userMessageId = userMessageId;
        this.traceId = traceId;
    }
}
