/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.invest.iagent.hook;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;

/**
 * Middleware that adds OpenTelemetry tracing to the agent lifecycle.
 *
 * <p>Produces spans for:
 * <ul>
 *   <li>{@code invoke_agent <name>} — wraps the entire reply</li>
 *   <li>{@code chat <model>} — wraps each model API call</li>
 *   <li>{@code execute_tool <name>} — wraps each tool execution</li>
 * </ul>
 *
 * <p>When no OTel SDK is configured (only the default no-op provider is
 * active), every hook short-circuits to {@code next.apply(input)} with
 * near-zero overhead.
 *
 * <p>Usage:
 * <pre>{@code
 * ReActAgent agent = ReActAgent.builder()
 *     .name("assistant")
 *     .model(model)
 *     .middleware(new OtelTracingMiddleware())
 *     .build();
 * }</pre>
 */
@Slf4j
public class TracingMiddleware implements MiddlewareBase {

    private final int textPrintMaxLength;

    public TracingMiddleware(int maxLength){
        this.textPrintMaxLength = maxLength ;
    }

    // ------------------------------------------------------------------
    // onAgent — invoke_agent span
    // ------------------------------------------------------------------

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    // log input
                    String inputMessagesStr = buildString(input);
                    log.info("onAgent input= {}",inputMessagesStr);
                    int length = length(inputMessagesStr);
                    log.info("onAgent inputSize= {}",length);
                    long startTime = System.currentTimeMillis();
                    return next.apply(input)
                            .doOnNext(
                                    event -> {
                                        if (event instanceof AgentStartEvent rse) {
                                            long elapsed = System.currentTimeMillis() - startTime;
                                            log.info("call agent done ::: output = {} , cost-millis = {}",buildString(rse),elapsed);
                                        }
                                    })
                            .doOnComplete(
                                    () -> {
                                        long elapsed = System.currentTimeMillis() - startTime;
                                        log.info("call agent done :::cost-millis = {}",elapsed);
                                    })
                            .doOnError(
                                    e -> {
                                        long elapsed = System.currentTimeMillis() - startTime;
                                        log.info("call agent failed ::: error = {} , cost-millis = {}",e.getMessage(),elapsed);
                                    });
                });
    }

    // ------------------------------------------------------------------
    // onModelCall — chat span
    // ------------------------------------------------------------------

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    String inputMessagesStr = buildString(input);
                    log.info("call model input= {}",inputMessagesStr);
                    long startTime = System.currentTimeMillis();
                    return next.apply(input)
                            .doOnNext(
                                    event -> {
                                        if (event instanceof ModelCallEndEvent mce) {
                                            long elapsed = System.currentTimeMillis() - startTime;
                                            log.info("call model done ::: output = {} , cost-millis = {}",buildString(mce),elapsed);
                                        }
                                    })
                            .doOnComplete(
                                    () -> {
                                        long elapsed = System.currentTimeMillis() - startTime;
                                        log.info("call model done :::cost-millis = {}",elapsed);
                                    })
                            .doOnError(
                                    e -> {
                                        long elapsed = System.currentTimeMillis() - startTime;
                                        log.info("call model failed ::: error = {} , cost-millis = {}",e.getMessage(),elapsed);
                                    });
             });
    }

    private int length(String... input){
        if(input == null){
            return 0 ;
        }

        return Arrays.stream(input)
                .filter(StringUtils::isNotBlank)
                .map(String::length)
                .reduce(Integer::sum).orElse(0);
    }

    private String buildString(Object response){
        if(Objects.isNull(response)){
            return null ;
        }
        String result = JSON.toJSONString(response) ;
        return truncate(result) ;
    }

    /**
     * 截断长文本，避免输出过多内容
     */
    private String truncate(String text){
        return this.truncate(text,textPrintMaxLength) ;
    }

    /**
     * 截断长文本，避免输出过多内容
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "<null>";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "... [truncated, total: " + text.length() + " chars]";
    }

    // ------------------------------------------------------------------
    // onActing — execute_tool span
    // ------------------------------------------------------------------

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    String toolNames =
                            input.toolCalls() != null
                                    ? input.toolCalls().stream()
                                    .map(ToolUseBlock::getName)
                                    .collect(Collectors.joining(", "))
                                    : "unknown";
                    String params = buildString(input);
                    log.info("call tool input= {}, {}",toolNames,params);
                    long startTime = System.currentTimeMillis();
                    return next.apply(input)
                            .doOnNext(
                                    event -> {
                                        if (event instanceof ToolResultEndEvent mce) {
                                            long elapsed = System.currentTimeMillis() - startTime;
                                            log.info("call tool done ::: tool={},output = {} , cost-millis = {}",toolNames,buildString(mce),elapsed);
                                        }
                                    })
                            .doOnComplete(
                                    () -> {
                                        long elapsed = System.currentTimeMillis() - startTime;
                                        log.info("call tool done :::cost-millis = {}",elapsed);
                                    })
                            .doOnError(
                                    e -> {
                                        long elapsed = System.currentTimeMillis() - startTime;
                                        log.info("call tool failed ::: error = {} , cost-millis = {}",e.getMessage(),elapsed);
                                    });
                });
    }

}
