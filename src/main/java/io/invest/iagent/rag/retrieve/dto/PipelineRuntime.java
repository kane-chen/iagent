package io.invest.iagent.rag.retrieve.dto;


/**
 * Pipeline运行时信息
 *
 * @param messageId     消息ID 当前生成的助手消息ID
 * @param userMessageId 当前用户消息ID
 * @param traceId       链路追踪ID
 */
public record PipelineRuntime(String messageId, String userMessageId, String traceId) {

}
