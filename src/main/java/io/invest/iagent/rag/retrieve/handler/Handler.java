package io.invest.iagent.rag.retrieve.handler;

import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.ChatManage;

/**
 * 事件处理器
 */
public interface Handler {

    String name() ;

    void handle(PipelineContext ctx, ChatManage cm) ;

}
