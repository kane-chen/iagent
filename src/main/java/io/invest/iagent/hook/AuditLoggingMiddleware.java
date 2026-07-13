package io.invest.iagent.hook;
 
import com.alibaba.fastjson2.JSON;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
 
/**
 * 可观测中间件：结构化记录 agent / model / tool 的入参与结果。
 * 
 * <p>拦截五个阶段：
 * <ul>
 *   <li>onAgent     → 记录 agent 调用入参消息 & 完成状态</li>
 *   <li>onReasoning → 记录推理阶段的消息数、模型名</li>
 *   <li>onModelCall → 记录模型调用入参消息数 & 返回的文本/工具调用</li>
 *   <li>onActing    → 记录工具调用的名称、入参 & 执行结果</li>
 * </ul>
 */
public class AuditLoggingMiddleware implements MiddlewareBase {
 
    private static final Logger log = LoggerFactory.getLogger(AuditLoggingMiddleware.class);
 
    // ==================== 结构化记录模型 ====================
 
    public static class AgentCallRecord {
        public String agentName;
        public String sessionId;
        public long startTimeMs;
        public long endTimeMs;
        public String inputMessage ;
        public int inputMessageCount;
        public String status; // "success" | "error"
        public String errorMessage;
        public List<ModelCallRecord> modelCalls = new ArrayList<>();
        public List<ToolCallRecord> toolCalls = new ArrayList<>();
    }
 
    public static class ModelCallRecord {
        public long startTimeMs;
        public long endTimeMs;
        public String modelName;
        public String inputMessage ;
        public int inputMessageCount;
        public String responseText;       // 模型返回的文本（截断）
        public List<String> toolCallNames; // 模型请求的工具调用
    }
 
    public static class ToolCallRecord {
        public long startTimeMs;
        public long endTimeMs;
        public String toolCallId;
        public String toolName;
        public String arguments;          // 入参 JSON（截断）
        public String result;             // 执行结果（截断）
        public String state;              // ToolResultState
    }
 
    // ==================== 按线程/会话暂存记录 ====================
 
    private final ConcurrentHashMap<String, AgentCallRecord> activeRecords = new ConcurrentHashMap<>();

    // ==================== 工具方法 ====================

    private String truncate(String text){
        return truncate(text,textPrintMaxLength) ;
    }

    private String truncate(String s, int max) {
        if (s == null) return "<null>";
        return s.length() <= max ? s : s.substring(0, max) + "...(" + s.length() + " chars)";
    }
 
    private static String resolveModelName(Agent agent) {
        if (agent instanceof ReActAgent ra) {
            return ra.getModel() != null ? ra.getModel().toString() : "<unknown>";
        }
        return agent.getClass().getSimpleName();
    }

    private final int textPrintMaxLength;

    public AuditLoggingMiddleware(int maxLength){
        this.textPrintMaxLength = maxLength ;
    }

    // ==================== onAgent：整个 agent 调用 ====================
 
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
 
        String sessionId = ctx != null ? ctx.getSessionId() : "unknown";
        String agentName = agent.getName();
 
        // 创建记录
        AgentCallRecord record = new AgentCallRecord();
        record.agentName = agentName;
        record.sessionId = sessionId;
        record.startTimeMs = System.currentTimeMillis();
        record.inputMessage = JSON.toJSONString(input.msgs()) ;
        record.inputMessageCount = input.msgs() != null ? input.msgs().size() : 0;
        activeRecords.put(sessionId, record);
 
        log.info("[AUDIT][AGENT] START | agent={}, session={}, inputMessages={}",
                agentName, sessionId, record.inputMessageCount);
 
        // 记录入参消息摘要
        if (log.isDebugEnabled() && input.msgs() != null) {
            for (Msg msg : input.msgs()) {
                log.debug("[AUDIT][AGENT] INPUT | role={}, content={}",
                        msg.getRole(), truncate(JSON.toJSONString(msg)));
            }
        }
 
        return next.apply(input)
                .doOnComplete(() -> {
                    record.endTimeMs = System.currentTimeMillis();
                    record.status = "success";
                    log.info("[AUDIT][AGENT] END | agent={}, duration={}ms, modelCalls={}, toolCalls={}",
                            agentName,
                            record.endTimeMs - record.startTimeMs,
                            record.modelCalls.size(),
                            record.toolCalls.size());
                })
                .doOnError(e -> {
                    record.endTimeMs = System.currentTimeMillis();
                    record.status = "error";
                    record.errorMessage = e.getMessage();
                    log.error("[AUDIT][AGENT] ERROR | agent={}, error={}: {}",
                            agentName, e.getClass().getSimpleName(), e.getMessage());
                });
    }
 
    // ==================== onReasoning：推理阶段 ====================
 
    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
 
        String agentName = agent.getName();
        String modelName = resolveModelName(agent);
        int msgCount = input.messages() != null ? input.messages().size() : 0;
 
        log.info("[AUDIT][REASONING] START | agent={}, model={}, inputMessages={}",
                agentName, modelName, msgCount);
 
        // 记录发送给模型的消息摘要
        if (log.isDebugEnabled() && input.messages() != null) {
            for (Msg msg : input.messages()) {
                log.debug("[AUDIT][REASONING] INPUT | role={}, len={}",
                        msg.getRole(),
                        msg.getTextContent() != null ? msg.getTextContent().length() : 0
                );
//                log.debug("[AUDIT][REASONING] INPUT | role={}, len={},input={}",
//                        msg.getRole(),
//                        msg.getTextContent() != null ? msg.getTextContent().length() : 0,
//                        JSON.toJSONString(msg)
//                );
            }
        }
 
        // 收集推理结果
        StringBuilder textBuf = new StringBuilder();
        List<ToolCallStartEvent> toolCallStarts = new ArrayList<>();
 
        return next.apply(input)
                .doOnNext(ev -> {
                    if (ev instanceof TextBlockDeltaEvent tbd && tbd.getDelta() != null) {
                        textBuf.append(tbd.getDelta());
                    } else if (ev instanceof ToolCallStartEvent tcs) {
                        toolCallStarts.add(tcs);
                    }
                })
                .doOnComplete(() -> {
                    String text = textBuf.toString();
                    if (!text.isBlank()) {
                        log.info("[AUDIT][REASONING] TEXT | agent={}, text={}",
                                agentName, truncate(text));
                    }
                    if (!toolCallStarts.isEmpty()) {
                        List<String> names = toolCallStarts.stream()
                                .map(ToolCallStartEvent::getToolCallName)
                                .toList();
                        log.info("[AUDIT][REASONING] TOOL_CALLS | agent={}, count={}, names={}",
                                agentName, names.size(), names);
                    }
                });
    }
 
    // ==================== onModelCall：原始模型 API 调用 ====================
 
    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
 
        String agentName = agent.getName();
        String modelName = input.model() != null ? input.model().toString() : "<unknown>";
        int msgCount = input.messages() != null ? input.messages().size() : 0;
        int toolCount = input.tools() != null ? input.tools().size() : 0;
 
        // 创建模型调用记录
        ModelCallRecord modelRecord = new ModelCallRecord();
        modelRecord.startTimeMs = System.currentTimeMillis();
        modelRecord.modelName = modelName;
        modelRecord.inputMessage = JSON.toJSONString(input.messages()) ;
        modelRecord.inputMessageCount = msgCount;
 
        log.info("[AUDIT][MODEL] START | agent={}, model={}, messages={}, tools={}",
                agentName, modelName, msgCount, toolCount);
//        log.info("[AUDIT][MODEL] START | agent={}, model={}, messages={}, tools={}, content={}",
//                agentName, modelName, msgCount, toolCount, truncate(JSON.toJSONString(input.messages())));

        // 入参消息详情
        if (log.isDebugEnabled() && input.messages() != null) {
            for (int i = 0; i < input.messages().size(); i++) {
                Msg msg = input.messages().get(i);
                log.debug("[AUDIT][MODEL] INPUT | idx={}, role={}",
                        i, msg.getRole());
            }
        }
 
        // 收集模型输出
        StringBuilder responseText = new StringBuilder();
        List<String> toolCallNames = new ArrayList<>();
 
        return next.apply(input)
                .doOnNext(ev -> {
                    if (ev instanceof TextBlockDeltaEvent tbd && tbd.getDelta() != null) {
                        responseText.append(tbd.getDelta());
                    } else if (ev instanceof ToolCallStartEvent tcs) {
                        toolCallNames.add(tcs.getToolCallName());
                    }else{
                        responseText.append(JSON.toJSONString(ev)) ;
                    }
                })
                .doOnComplete(() -> {
                    modelRecord.endTimeMs = System.currentTimeMillis();
                    modelRecord.responseText = truncate(responseText.toString());
                    modelRecord.toolCallNames = toolCallNames;
 
                    // 挂到 agent 级记录
                    String sessionId = ctx != null ? ctx.getSessionId() : "unknown";
                    if(StringUtils.isNotBlank(sessionId)){
                        Optional.of(activeRecords).map(t -> t.get(sessionId))
                                .ifPresent(agentRecord -> agentRecord.modelCalls.add(modelRecord));
                    }

                    log.info("[AUDIT][MODEL] END | agent={}, model={}, duration={}ms, " +
                            "responseLen={}, toolCalls={},result={}",
                            agentName, modelName,
                            modelRecord.endTimeMs - modelRecord.startTimeMs,
                            responseText.length(), toolCallNames, modelRecord.responseText);
                });
    }
 
    // ==================== onActing：工具执行 ====================
 
    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
 
        String agentName = agent.getName();
 
        // 记录工具调用入参
        if (input.toolCalls() != null) {
            for (ToolUseBlock tu : input.toolCalls()) {
                log.info("[AUDIT][TOOL] START | agent={}, id={}, name={}",
                        agentName, tu.getId(), tu.getName());
 
                if (log.isDebugEnabled()) {
                    log.debug("[AUDIT][TOOL] ARGS_FULL | id={}, input={}",
                            tu.getId(), JSON.toJSONString(tu.getInput()));
                }
            }
        }
 
        // 收集工具执行结果（从事件流中提取）
        Map<String, String> toolNames = new ConcurrentHashMap<>();
        Map<String, StringBuilder> toolResults = new ConcurrentHashMap<>();
        Map<String, Long> toolStartTimes = new ConcurrentHashMap<>();

        if (input.toolCalls() != null) {
            for (ToolUseBlock tu : input.toolCalls()) {
                toolNames.put(tu.getId(), tu.getName());
                toolStartTimes.put(tu.getId(), System.currentTimeMillis());
            }
        }

        return next.apply(input)
                .doOnNext(ev -> {
                    if (ev instanceof ToolResultStartEvent start) {
                        toolNames.putIfAbsent(start.getToolCallId(), start.getToolCallName());
                        toolResults.computeIfAbsent(start.getToolCallId(), k -> new StringBuilder());
                    } else if (ev instanceof ToolResultTextDeltaEvent delta && delta.getDelta() != null) {
                        toolResults.computeIfAbsent(delta.getToolCallId(), k -> new StringBuilder())
                                .append(delta.getDelta());
                    } else if (ev instanceof ToolResultEndEvent end) {
                        String id = end.getToolCallId();
                        String toolName = toolNames.getOrDefault(id, "<unknown>");
                        String result = toolResults.containsKey(id)
                                ? toolResults.get(id).toString() : "";
                        Long startTs = toolStartTimes.get(id);
 
                        // 记录结构化数据
                        ToolCallRecord toolRecord = new ToolCallRecord();
                        toolRecord.toolCallId = id;
                        toolRecord.toolName = toolName;
                        toolRecord.startTimeMs = startTs != null ? startTs : 0;
                        toolRecord.endTimeMs = System.currentTimeMillis();
                        toolRecord.result = truncate(result);
                        toolRecord.state = end.getState() != null ? end.getState().name() : null;
 
                        // 挂到 agent 级记录
                        String sessionId = ctx != null ? ctx.getSessionId() : "unknown";
                        if(StringUtils.isNotBlank(sessionId)) {
                            Optional.of(activeRecords).map(t -> t.get(sessionId))
                                    .ifPresent(agentRecord -> agentRecord.toolCalls.add(toolRecord));
                        }
                        log.info("[AUDIT][TOOL] END | agent={}, id={}, name={}, " +
                                "duration={}ms, state={}, resultLen={}",
                                agentName, id, toolName,
                                startTs != null ? (System.currentTimeMillis() - startTs) : -1,
                                toolRecord.state, result.length());
 
                        if (log.isDebugEnabled()) {
                            log.debug("[AUDIT][TOOL] RESULT_FULL | id={}, result={}",
                                    id, toolRecord.result);
                        }
                    }
                });
    }
 
}