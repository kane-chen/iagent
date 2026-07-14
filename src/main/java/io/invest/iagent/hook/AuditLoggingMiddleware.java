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
import java.util.regex.Pattern;

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

    // ==================== 撞墙循环保护 ====================
    // 场景：LLM 在 ShellCommandTool 触发 SecurityError（管道/重定向/多命令）后不"投降"，
    // 反复用同类命令重试，直到超时或 JVM 关掉（真实事故：AgentFilingTest#test_excel_83690）。
    // 策略：按 session 记录 SecurityError；
    //  - 同一命令签名（去参数化后）连续 sameCmdThreshold 次 → 抛异常打断本次 acting Flux；
    //  - 累计 totalThreshold 次 → 抛异常打断本次 acting Flux。
    // 抛出的异常会顺着 Flux 上浮到 agent 主循环，终止本轮任务，避免撞墙循环。

    /** SecurityError 识别关键字（ShellCommandTool 拒绝命令时的错误文本）。 */
    private static final Pattern SECURITY_ERROR_PATTERN =
            Pattern.compile("SecurityError:.*(multiple command separators|approval callback)",
                    Pattern.CASE_INSENSITIVE);

    /** 命令参数占位：数字、单引号、双引号内内容替换后作为"命令模板"用于同类命令识别。 */
    private static final Pattern CMD_ARG_PATTERN =
            Pattern.compile("\"[^\"]*\"|'[^']*'|\\b\\d+\\b|\\s+");

    /** 同一 session 的 SecurityError 计数 & 最近命令签名，用于识别撞墙循环。 */
    private static class SecurityErrorTracker {
        String lastCmdSignature;
        int sameCmdStreak;
        int totalCount;
    }

    private final ConcurrentHashMap<String, SecurityErrorTracker> securityErrorTrackers = new ConcurrentHashMap<>();

    /** 同一命令签名连续触发 SecurityError 的次数上限；超过则中断 acting。 */
    private final int sameCmdSecurityErrorThreshold;

    /** 同一 session 内累计 SecurityError 次数上限；超过则中断 acting。 */
    private final int totalSecurityErrorThreshold;

    public AuditLoggingMiddleware(int maxLength){
        this(maxLength, 3, 8) ;
    }

    /**
     * @param maxLength                     日志中截断的最大字符数
     * @param sameCmdSecurityErrorThreshold 同一命令签名连续 SecurityError 次数上限（默认 3）
     * @param totalSecurityErrorThreshold   同 session 累计 SecurityError 次数上限（默认 8）
     */
    public AuditLoggingMiddleware(int maxLength,
                                  int sameCmdSecurityErrorThreshold,
                                  int totalSecurityErrorThreshold){
        this.textPrintMaxLength = maxLength ;
        this.sameCmdSecurityErrorThreshold = sameCmdSecurityErrorThreshold ;
        this.totalSecurityErrorThreshold = totalSecurityErrorThreshold ;
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

//                        if (log.isDebugEnabled()) {
//                            log.debug("[AUDIT][TOOL] RESULT_FULL | id={}, result={}",
//                                    id, toolRecord.result);
//                        }

                        // 撞墙循环检测：若本次 tool 结果里含 SecurityError，则按 session 累计
                        checkSecurityErrorLoop(agentName, sessionId, toolName,
                                lookupToolArgs(input, id), result);
                    }
                });
    }

    // ==================== 撞墙循环保护辅助方法 ====================

    /** 从 ActingInput 里取指定 toolCallId 的入参 JSON（用于生成命令签名）。 */
    private String lookupToolArgs(ActingInput input, String toolCallId) {
        if (input == null || input.toolCalls() == null || toolCallId == null) {
            return "";
        }
        for (ToolUseBlock tu : input.toolCalls()) {
            if (toolCallId.equals(tu.getId())) {
                Object args = tu.getInput();
                return args != null ? JSON.toJSONString(args) : "";
            }
        }
        return "";
    }

    /**
     * 从 shell 命令中抽出"命令签名"：去掉引号内内容、数字、多余空白，
     * 让 "python xxx --ticker 83690 --question \"A\"" 与
     * "python xxx --ticker 00700 --question \"B\"" 归为同一签名。
     */
    private String buildCmdSignature(String toolName, String argsJson) {
        if (StringUtils.isBlank(argsJson)) {
            return toolName + "|<no-args>";
        }
        String normalized = CMD_ARG_PATTERN.matcher(argsJson).replaceAll(" ").trim();
        if (normalized.length() > 256) {
            normalized = normalized.substring(0, 256);
        }
        return toolName + "|" + normalized;
    }

    /**
     * 结果里出现 SecurityError 时累计计数；触发阈值则抛异常终止本次 acting。
     * 抛出的 SecurityErrorLoopException 会通过 Flux 上浮，被 agent 主循环视为工具执行失败，
     * 避免 LLM 在同一撞墙模式上继续重试。
     */
    private void checkSecurityErrorLoop(String agentName, String sessionId,
                                        String toolName, String argsJson, String result) {
        if (StringUtils.isBlank(result) || !SECURITY_ERROR_PATTERN.matcher(result).find()) {
            // 非 SecurityError：不重置计数（其他失败可能夹在中间），只在阈值命中时才动作
            return;
        }
        if (StringUtils.isBlank(sessionId)) {
            sessionId = "unknown";
        }
        String signature = buildCmdSignature(toolName, argsJson);

        SecurityErrorTracker tracker = securityErrorTrackers.computeIfAbsent(
                sessionId, k -> new SecurityErrorTracker());

        synchronized (tracker) {
            tracker.totalCount++;
            if (signature.equals(tracker.lastCmdSignature)) {
                tracker.sameCmdStreak++;
            } else {
                tracker.lastCmdSignature = signature;
                tracker.sameCmdStreak = 1;
            }

            log.warn("[AUDIT][GUARD] SecurityError observed | agent={}, session={}, tool={}, " +
                            "sameCmdStreak={}/{}, total={}/{}, signature={}",
                    agentName, sessionId, toolName,
                    tracker.sameCmdStreak, sameCmdSecurityErrorThreshold,
                    tracker.totalCount, totalSecurityErrorThreshold,
                    signature);

            boolean tripSameCmd = tracker.sameCmdStreak >= sameCmdSecurityErrorThreshold;
            boolean tripTotal = tracker.totalCount >= totalSecurityErrorThreshold;
            if (tripSameCmd || tripTotal) {
                String reason = tripSameCmd
                        ? "同一命令签名连续 " + tracker.sameCmdStreak + " 次触发 SecurityError"
                        : "本次会话累计 " + tracker.totalCount + " 次 SecurityError";
                log.error("[AUDIT][GUARD] Wall-hit loop detected, aborting acting flux | " +
                                "agent={}, session={}, reason={}",
                        agentName, sessionId, reason);
                // 重置，避免同 session 下一次 acting 立刻再次抛出
                tracker.sameCmdStreak = 0;
                tracker.totalCount = 0;
                tracker.lastCmdSignature = null;
                throw new SecurityErrorLoopException(reason +
                        "，工具调用已被审计中间件中止。请改换调用方式，或按 skill 规范传参。");
            }
        }
    }

    /** 用于向 agent 主循环上报"撞墙循环已被中断"，避免 LLM 继续重试。 */
    public static class SecurityErrorLoopException extends RuntimeException {
        public SecurityErrorLoopException(String message) {
            super(message);
        }
    }

}