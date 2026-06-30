package io.invest.iagent.hook;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 日志追踪器
 * 用于跟踪：
 * - LLM 模型调用的输入和输出
 * - Agent 调用的输入和输出
 * - Tool 工具调用的输入和输出
 * - 消息格式化的输入和输出
 */
@Slf4j
public class LoggingTracer implements Tracer {
    private final int textPrintMaxLength ;

    public LoggingTracer(int maxLength){
        this.textPrintMaxLength = maxLength ;
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
    

    @Override
    public Flux<ChatResponse> callModel(
            ChatModelBase instance, 
            List<Msg> inputMessages, 
            List<ToolSchema> toolSchemas, 
            GenerateOptions options, 
            Supplier<Flux<ChatResponse>> modelCall) {

        // log input
        String inputMessagesStr = buildString(inputMessages);
        String toolSchemasStr = buildString(toolSchemas);
        log.info("call model ::: input= {}, tools= {}, tokens = {}",inputMessagesStr,toolSchemasStr,length(inputMessagesStr,toolSchemasStr));
        // execute
        long startTime = System.currentTimeMillis();
        return modelCall.get()
                // log output
                .doOnNext(response -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("call model done ::: output = {} , cost-millis = {}",buildString(response),elapsed);
                })
                // log error
                .doOnError(error -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("call model failed ::: error = {} , cost-millis = {}",error.getMessage(),elapsed);
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
        try {
            String result = new ObjectMapper().writeValueAsString(response) ;
            return truncate(result) ;
        } catch (JsonProcessingException e) {
            return ToStringBuilder.reflectionToString(response, ToStringStyle.MULTI_LINE_STYLE) ;
        }
    }

    @Override
    public Mono<Msg> callAgent(
            AgentBase instance, 
            List<Msg> inputMessages, 
            Supplier<Mono<Msg>> agentCall) {
        log.info("call agent ::: input = {}",buildString(inputMessages));
        long startTime = System.currentTimeMillis();
        return agentCall.get()
                // log output
                .doOnSuccess(response -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("call agent ::: output = {}, cost = {}",buildString(response),elapsed);

                })
                .doOnError(error -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.error("call agent error::: output = {}, cost = {}",error.getMessage(),elapsed);
                });
    }

    @Override
    public Mono<ToolResultBlock> callTool(
            Toolkit toolkit, 
            ToolCallParam toolCallParam, 
            Supplier<Mono<ToolResultBlock>> toolKitCall) {
        
        log.info("call tool ::: input = {}", Optional.ofNullable(toolCallParam.getInput()).map(JSON::toJSONString).orElse(null));
        long startTime = System.currentTimeMillis();
        return toolKitCall.get()
            .doOnSuccess(result -> {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("call tool ::: output = {},cost={}", buildString(result),elapsed);

            })
            .doOnError(error -> {
                long elapsed = System.currentTimeMillis() - startTime;
                log.error("call tool error::: output = {},cost={}", error.getMessage(),elapsed);
            });
    }

//    @Override
//    public <TReq, TResp, TParams> List<TReq> callFormat(
//            AbstractBaseFormatter<TReq, TResp, TParams> formatter,
//            List<Msg> messages,
//            Supplier<List<TReq>> formatCall) {
//        log.info("call format ::: input= {},formatter={}",buildString(messages),formatter.getClass().getSimpleName());
//        long startTime = System.currentTimeMillis();
//        List<TReq> result = formatCall.get();
//        long elapsed = System.currentTimeMillis() - startTime;
//        log.info("call format ::: output = {},cost={}", buildString(result),elapsed);
//        return result;
//    }

//    @Override
//    public <TResp> TResp runWithContext(ContextView reactorCtx, Supplier<TResp> inner) {
//        log.info("call runWithContext ::: input= {}",buildString(reactorCtx));
//        long startTime = System.currentTimeMillis();
//
//        try {
//            TResp result = inner.get();
//            long elapsed = System.currentTimeMillis() - startTime;
//            log.info("call runWithContext ::: output = {},cost={}", buildString(result),elapsed);
//            return result;
//        } catch (Exception e) {
//            long elapsed = System.currentTimeMillis() - startTime;
//            log.error("call runWithContext error::: output = {},cost={}", e.getMessage(),elapsed);
//            throw e;
//        }
//    }
}
