package io.invest.iagent.rag.retrieve.handler;

import io.invest.iagent.rag.retrieve.dto.PipelineRuntime;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;

/**
 * 事件处理器
 */
public interface Handler {

    String name() ;

    void handle(PipelineRuntime runtime, PipelineContext context) ;

}
