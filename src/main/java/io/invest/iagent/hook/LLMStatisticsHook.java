package io.invest.iagent.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.model.ChatUsage;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class LLMStatisticsHook implements Hook {
    private final long startTime = System.currentTimeMillis();
    private final List<LLMCallRecord> callRecords = new ArrayList<>();
    private final AtomicInteger totalCalls = new AtomicInteger(0);
    private final ConcurrentHashMap<String, ModelStats> modelStatsMap = new ConcurrentHashMap<>();
    private LLMCallRecord currentCallRecord;

    // ANSI 颜色代码
    private static final String RESET = "\u001B[0m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String RED = "\u001B[31m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String WHITE = "\u001B[37m";

    public static class LLMCallRecord {
        String agentName;
        String modelName;
        long startTime;
        long endTime;
        long duration;
        int inputTokens;
        int outputTokens;
        int totalTokens;
        boolean success;

        LLMCallRecord(String agentName, String modelName) {
            this.agentName = agentName;
            this.modelName = modelName;
            this.startTime = System.currentTimeMillis();
            this.success = true;
        }

        void complete(int inputTokens, int outputTokens) {
            this.endTime = System.currentTimeMillis();
            this.duration = this.endTime - this.startTime;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.totalTokens = inputTokens + outputTokens;
        }

        void fail() {
            this.endTime = System.currentTimeMillis();
            this.duration = this.endTime - this.startTime;
            this.success = false;
        }
    }

    public static class ModelStats {
        String modelName;
        int callCount = 0;
        long totalDuration = 0;
        long avgDuration = 0;
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalTokens = 0;

        ModelStats(String modelName) {
            this.modelName = modelName;
        }

        void addCall(long duration, int inputTokens, int outputTokens) {
            callCount++;
            totalDuration += duration;
            avgDuration = totalDuration / callCount;
            totalInputTokens += inputTokens;
            totalOutputTokens += outputTokens;
            totalTokens += (inputTokens + outputTokens);
        }
    }

    @Override
    public int priority() {
        return 60;
    }

    @Override
    public synchronized <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent e) {
            String agentName = event.getAgent().getName();
            String modelName = e.getModelName();
            
            currentCallRecord = new LLMCallRecord(agentName, modelName);
            callRecords.add(currentCallRecord);
            totalCalls.incrementAndGet();

        } else if (event instanceof PostReasoningEvent e) {
            if (currentCallRecord != null) {
                try {
                    int inputTokens = extractInputTokens(e);
                    int outputTokens = extractOutputTokens(e);
                    currentCallRecord.complete(inputTokens, outputTokens);
                    
                    modelStatsMap.computeIfAbsent(currentCallRecord.modelName, ModelStats::new)
                            .addCall(currentCallRecord.duration, inputTokens, outputTokens);
                } catch (Exception ex) {
                    currentCallRecord.fail();
                } finally {
                    currentCallRecord = null;
                }
            }

        } else if (event instanceof PostCallEvent e) {
            printStatistics(event.getAgent().getName());
        }

        return Mono.just(event);
    }

    private int extractInputTokens(PostReasoningEvent event) {
        try {
            var msg = event.getReasoningMessage();
            if (msg != null && msg.getMetadata() != null) {
                var metadata = msg.getMetadata();
                if (metadata.containsKey("_chat_usage")) {
                    var usage = metadata.get("_chat_usage");
                    if (usage instanceof ChatUsage usage1) {
                        return usage1.getInputTokens() ;
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private int extractOutputTokens(PostReasoningEvent event) {
        try {
            var msg = event.getReasoningMessage();
            if (msg != null && msg.getMetadata() != null) {
                var metadata = msg.getMetadata();
                if (metadata.containsKey("_chat_usage")) {
                    var usage = metadata.get("_chat_usage");
                    if (usage instanceof ChatUsage usage1) {
                        return usage1.getOutputTokens() ;
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private void printStatistics(String agentName) {
        long totalElapsed = System.currentTimeMillis() - startTime;
        
        System.out.println("\n" + "═".repeat(100));
        System.out.println(BOLD + WHITE + "  📊 LLM CALL STATISTICS REPORT" + RESET);
        System.out.println(DIM + "  Agent: " + agentName + RESET);
        System.out.println(DIM + "  Total Duration: " + formatDuration(totalElapsed) + RESET);
        System.out.println("═".repeat(100));
        
        if (callRecords.isEmpty()) {
            System.out.println(YELLOW + "  No LLM calls recorded." + RESET);
            System.out.println("═".repeat(100) + "\n");
            return;
        }

        long totalDuration = callRecords.stream().mapToLong(r -> r.duration).sum();
        int totalInputTokens = callRecords.stream().mapToInt(r -> r.inputTokens).sum();
        int totalOutputTokens = callRecords.stream().mapToInt(r -> r.outputTokens).sum();
        int totalTokens = callRecords.stream().mapToInt(r -> r.totalTokens).sum();
        int successfulCalls = (int) callRecords.stream().filter(r -> r.success).count();
        int failedCalls = totalCalls.get() - successfulCalls;

        System.out.println("\n" + BOLD + CYAN + "  📈 Overall Statistics:" + RESET);
        System.out.println(DIM + "  ┌─────────────────────────────────────────────────────────┐" + RESET);
        System.out.println(DIM + "  │ " + WHITE + "Total Calls:       " + BOLD + String.format("%-43d", totalCalls.get()) + DIM + "│" + RESET);
        System.out.println(DIM + "  │ " + WHITE + "Successful:        " + GREEN + BOLD + String.format("%-43d", successfulCalls) + DIM + "│" + RESET);
        if (failedCalls > 0) {
            System.out.println(DIM + "  │ " + WHITE + "Failed:            " + RED + BOLD + String.format("%-43d", failedCalls) + DIM + "│" + RESET);
        } else {
            System.out.println(DIM + "  │ " + WHITE + "Failed:            " + GREEN + String.format("%-43d", 0) + DIM + "│" + RESET);
        }
        System.out.println(DIM + "  │ " + WHITE + "Total Duration:    " + BOLD + String.format("%-43s", formatDuration(totalDuration)) + DIM + "│" + RESET);
        System.out.println(DIM + "  │ " + WHITE + "Avg Duration:      " + BOLD + String.format("%-43s", formatDuration(totalDuration / totalCalls.get())) + DIM + "│" + RESET);
        System.out.println(DIM + "  ├─────────────────────────────────────────────────────────┤" + RESET);
        System.out.println(DIM + "  │ " + WHITE + "Total Tokens:      " + BOLD + String.format("%-43d", totalTokens) + DIM + "│" + RESET);
        System.out.println(DIM + "  │ " + WHITE + "  Input Tokens:    " + CYAN + String.format("%-43d", totalInputTokens) + DIM + "│" + RESET);
        System.out.println(DIM + "  │ " + WHITE + "  Output Tokens:   " + GREEN + String.format("%-43d", totalOutputTokens) + DIM + "│" + RESET);
        System.out.println(DIM + "  └─────────────────────────────────────────────────────────┘" + RESET);

        if (!modelStatsMap.isEmpty()) {
            System.out.println("\n" + BOLD + MAGENTA + "  🤖 Model Breakdown:" + RESET);
            System.out.println(DIM + "  ┌" + "─".repeat(96) + "┐" + RESET);
            System.out.println(DIM + "  │ " + BOLD + WHITE + String.format("%-20s", "Model") + 
                    String.format("%-8s", "Calls") + 
                    String.format("%-15s", "Duration") + 
                    String.format("%-12s", "Avg(ms)") + 
                    String.format("%-12s", "InTok") + 
                    String.format("%-12s", "OutTok") + 
                    String.format("%-12s", "Total") + DIM + "│" + RESET);
            System.out.println(DIM + "  ├" + "─".repeat(96) + "┤" + RESET);

            modelStatsMap.values().stream()
                    .sorted((a, b) -> Long.compare(b.totalDuration, a.totalDuration))
                    .forEach(stats -> {
                        String modelDisplay = truncate(stats.modelName, 18);
                        System.out.println(DIM + "  │ " + 
                                WHITE + String.format("%-20s", modelDisplay) + 
                                CYAN + String.format("%-8d", stats.callCount) + 
                                YELLOW + String.format("%-15s", formatDuration(stats.totalDuration)) + 
                                GREEN + String.format("%-12d", stats.avgDuration) + 
                                MAGENTA + String.format("%-12d", stats.totalInputTokens) + 
                                GREEN + String.format("%-12d", stats.totalOutputTokens) + 
                                BOLD + String.format("%-12d", stats.totalTokens) + 
                                DIM + "│" + RESET);
                    });

            System.out.println(DIM + "  └" + "─".repeat(96) + "┘" + RESET);
        }

        if (totalCalls.get() <= 20) {
            System.out.println("\n" + BOLD + YELLOW + "  📝 Call Details:" + RESET);
            System.out.println(DIM + "  ┌" + "─".repeat(96) + "┐" + RESET);
            System.out.println(DIM + "  │ " + BOLD + WHITE + String.format("%-4s", "#") + 
                    String.format("%-25s", "Model") + 
                    String.format("%-12s", "Duration") + 
                    String.format("%-10s", "Status") + 
                    String.format("%-12s", "InTok") + 
                    String.format("%-12s", "OutTok") + 
                    String.format("%-12s", "Total") + DIM + "│" + RESET);
            System.out.println(DIM + "  ├" + "─".repeat(96) + "┤" + RESET);

            for (int i = 0; i < callRecords.size(); i++) {
                LLMCallRecord record = callRecords.get(i);
                String status = record.success ? GREEN + "✓ OK" : RED + "✗ FAIL";
                String modelDisplay = truncate(record.modelName, 23);
                
                System.out.println(DIM + "  │ " + 
                        YELLOW + String.format("%-4d", i + 1) + 
                        WHITE + String.format("%-25s", modelDisplay) + 
                        CYAN + String.format("%-12s", formatDuration(record.duration)) + 
                        status + String.format("%-6s", "") + 
                        MAGENTA + String.format("%-12d", record.inputTokens) + 
                        GREEN + String.format("%-12d", record.outputTokens) + 
                        BOLD + String.format("%-12d", record.totalTokens) + 
                        DIM + "│" + RESET);
            }

            System.out.println(DIM + "  └" + "─".repeat(96) + "┘" + RESET);
        }

        System.out.println("═".repeat(100) + "\n");
    }

    private String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        } else if (millis < 60000) {
            return String.format("%.2fs", millis / 1000.0);
        } else {
            long minutes = millis / 60000;
            long seconds = (millis % 60000) / 1000;
            return String.format("%dm %ds", minutes, seconds);
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "<null>";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }
}
