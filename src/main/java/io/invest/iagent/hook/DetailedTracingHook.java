package io.invest.iagent.hook;

import io.agentscope.core.hook.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class DetailedTracingHook implements Hook {
        private final AtomicInteger iterationCounter = new AtomicInteger(0);
        private final long startTime = System.currentTimeMillis();
        private final Map<String, Long> toolCallStartTimes = new ConcurrentHashMap<>();
        private int maxMessageLength = 200;

        // ANSI 颜色代码
        private static final String RESET = "\u001B[0m";
        private static final String CYAN = "\u001B[36m";
        private static final String GREEN = "\u001B[32m";
        private static final String YELLOW = "\u001B[33m";
        private static final String MAGENTA = "\u001B[35m";
        private static final String RED = "\u001B[31m";
        private static final String BOLD = "\u001B[1m";
        private static final String DIM = "\u001B[2m";

        public DetailedTracingHook() {
            this(200) ;
        }

        public DetailedTracingHook(int maxMessageLength) {
                this.maxMessageLength = maxMessageLength;
        }

        @Override
        public int priority() {
            return 50;
        }

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            String agentName = event.getAgent().getName();
            long elapsed = System.currentTimeMillis() - startTime;
            String timestamp = String.format("[%04dms]", elapsed);

            if (event instanceof PreCallEvent e) {
                printHeader(timestamp, agentName, "PRE_CALL", CYAN);
                System.out.println(DIM + "  Input Messages: " +
                        (e.getInputMessages() != null ? e.getInputMessages().size() : 0) + RESET);

            } else if (event instanceof PostCallEvent e) {
                printHeader(timestamp, agentName, "POST_CALL", GREEN);
                String response = e.getFinalMessage() != null ?
                        truncate(e.getFinalMessage().getTextContent(), -1) : "<null>";
                System.out.println(DIM + "  Response: " + response + RESET);
                System.out.println(DIM + "  Total Iterations: " + iterationCounter.get() + RESET);
                System.out.println(DIM + "  Elapsed Time: " + elapsed + "ms" + RESET);

            } else if (event instanceof PreReasoningEvent e) {
                int iter = iterationCounter.incrementAndGet();
                printHeader(timestamp, agentName, "REASONING #" + iter, MAGENTA);
                System.out.println(DIM + "  Model: " + e.getModelName() + RESET);
                System.out.println(DIM + "  Context Messages: " +
                        (e.getInputMessages() != null ? e.getInputMessages().size() : 0) + RESET);

            } else if (event instanceof PostReasoningEvent e) {
                Msg msg = e.getReasoningMessage();
                if (msg != null) {
                    var toolCalls = msg.getContentBlocks(ToolUseBlock.class);
                    if (!toolCalls.isEmpty()) {
                        printHeader(timestamp, agentName, "TOOL_CALLS", YELLOW);
                        for (ToolUseBlock tool : toolCalls) {
                            System.out.println(GREEN + "  📞 Tool: " + tool.getName() + RESET);
                            System.out.println(DIM + "     ID: " + tool.getId() + RESET);

                            // 记录工具调用开始时间
                            toolCallStartTimes.put(tool.getId(), System.currentTimeMillis());

                            System.out.println(DIM + "     Args: " +
                                    truncate(tool.getInput().toString(), -1) + RESET);
                        }
                    } else {
                        String text = truncate(msg.getTextContent(), -1);
                        printHeader(timestamp, agentName, "TEXT_RESPONSE", GREEN);
                        System.out.println(DIM + "  Content: " + text + RESET);
                    }
                }

            } else if (event instanceof PreActingEvent e) {
                ToolUseBlock tool = e.getToolUse();
                if (tool != null) {
                    printHeader(timestamp, agentName, "EXECUTING_TOOL", YELLOW);
                    System.out.println(CYAN + "  🔧 Tool: " + tool.getName() + RESET);
                    System.out.println(DIM + "     ID: " + tool.getId() + RESET);
                    System.out.println(DIM + "     Input: " +
                            truncate(tool.getInput().toString(), -1) + RESET);

                    // 检查是否是 SubAgent 调用
                    if (tool.getName().startsWith("call_")) {
                        System.out.println(YELLOW + BOLD + "  ⏱️  SubAgent execution started..." + RESET);
                    }
                }

            } else if (event instanceof PostActingEvent e) {
                ToolUseBlock tool = e.getToolUse();
                ToolResultBlock result = e.getToolResult();
                if (tool != null) {
                    // 计算工具执行耗时
                    Long startTime = toolCallStartTimes.remove(tool.getId());
                    String durationStr = "";
                    if (startTime != null) {
                        long duration = System.currentTimeMillis() - startTime;
                        durationStr = String.format(" | ⏱️ Duration: %dms", duration);

                        // 如果耗时超过 1 秒，高亮显示
                        if (duration > 1000) {
                            durationStr = RED + BOLD + durationStr + RESET;
                        }
                    }

                    printHeader(timestamp, agentName, "TOOL_RESULT", GREEN);
                    System.out.println(CYAN + "  ✅ Tool: " + tool.getName() + durationStr + RESET);
                    System.out.println(DIM + "     ID: " + tool.getId() + RESET);

                    // 如果是 SubAgent，提取 session_id
                    if (tool.getName().startsWith("call_") && result != null) {
                        String output = result.getOutput().get(0).toString();
                        if (output.contains("session_id:")) {
                            int start = output.indexOf("session_id:") + "session_id:".length();
                            int end = output.indexOf("\n", start);
                            if (end == -1) end = output.length();
                            String sessionId = output.substring(start, end).trim();
                            System.out.println(YELLOW + "     📝 SubAgent Session: " + sessionId + RESET);
                            System.out.println(DIM + "     💡 Tip: Check session file for detailed execution log:" + RESET);
                            System.out.println(DIM + "        .agentscope/workspace/*/agents/" +
                                    tool.getName().replace("call_", "") + "/sessions/" + sessionId + ".jsonl" + RESET);
                        }
                    }

                    if (result != null && !result.getOutput().isEmpty()) {
                        String output = result.getOutput().get(0).toString();
                        System.out.println(DIM + "     Output Length: " + output.length() + " chars" + RESET);
                        System.out.println(DIM + "     Preview: " + truncate(output, -1) + RESET);
                    }
                }
            } else if (event instanceof PostSummaryEvent e) {
                printHeader(timestamp, agentName, "POST_SUMMARY", GREEN);
                String response = e.getSummaryMessage().getTextContent();
                System.out.println(DIM + "  Response: " + response + RESET);
                System.out.println(DIM + "  Total Iterations: " + iterationCounter.get() + RESET);
                System.out.println(DIM + "  Elapsed Time: " + elapsed + "ms" + RESET);

            } else if (event instanceof ErrorEvent e) {
                printHeader(timestamp, agentName, "ERROR", RED);
                System.out.println(RED + "  ❌ Error: " + e.getError().getMessage() + RESET);
                e.getError().printStackTrace();
            }

            return Mono.just(event);
        }

        private void printHeader(String timestamp, String agent, String eventType, String color) {
            System.out.println("\n" + DIM + "─".repeat(80) + RESET);
            System.out.println(color + BOLD + "  " + timestamp + " " +
                    agent + " | " + eventType + RESET);
        }

        private String truncate(String text, int maxLength) {
            if(maxLength <= 0 ){
                maxLength = this.maxMessageLength ;
            }
            if (text == null) return "<null>";
            if (text.length() <= maxLength) return text;
            return text.substring(0, maxLength) + "... [truncated]";
        }
    }