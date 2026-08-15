package io.invest.iagent.rag.retrieve.model;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.util.concurrent.ConcurrentHashMap;

// 简单的事件总线，用于插件与Handler之间传递流式事件（模拟SSE）
public class EventBus {
    private final ConcurrentHashMap<String, Sinks.Many<String>> sinks = new ConcurrentHashMap<>();

    public void emit(String sessionId, String event) {
        sinks.computeIfAbsent(sessionId, k -> Sinks.many().multicast().onBackpressureBuffer())
                .tryEmitNext(event);
    }

    public Flux<String> stream(String sessionId) {
        return sinks.computeIfAbsent(sessionId, k -> Sinks.many().multicast().onBackpressureBuffer())
                .asFlux();
    }

    public void complete(String sessionId) {
        Sinks.Many<String> sink = sinks.get(sessionId);
        if (sink != null) sink.tryEmitComplete();
    }
}
