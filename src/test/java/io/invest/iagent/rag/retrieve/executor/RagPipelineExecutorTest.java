package io.invest.iagent.rag.retrieve.executor;

import io.invest.iagent.rag.config.RagConfig;
import io.invest.iagent.rag.model.RetrieveRequest;
import io.invest.iagent.rag.retrieve.dto.*;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.model.EventBus;
import io.invest.iagent.rag.retrieve.plugins.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 轻量冒烟测试：验证 PluginsManager 责任链构建与执行顺序，
 * 不依赖 LLM/数据库等外部服务。
 */
class RagPipelineExecutorTest {

    @Test
    void test_chain_invokes_plugins_in_order() throws PluginException {
        List<String> order = new ArrayList<>();

        Plugin first = new RecordingPlugin("first", EventType.QUERY_UNDERSTAND, order);
        Plugin second = new RecordingPlugin("second", EventType.QUERY_UNDERSTAND, order);

        PluginsManager manager = new PluginsManager(List.of(first, second));

        PipelineRequest req = buildRequest("什么是RAG?");
        PipelineContext ctx = new PipelineContext(new EventBus(), null, null, "trace-1");
        ChatManage cm = new ChatManage(req, new PipelineState(), ctx);

        manager.trigger(ctx, EventType.QUERY_UNDERSTAND, cm);

        assertThat(order).containsExactly("first", "second");
    }

    @Test
    void test_chain_returns_none_when_no_listeners() throws PluginException {
        PluginsManager manager = new PluginsManager(List.of());
        PipelineRequest req = buildRequest("q");
        PipelineContext ctx = new PipelineContext(new EventBus(), null, null, "trace-2");
        ChatManage cm = new ChatManage(req, new PipelineState(), ctx);

        PluginErrorOrNone result = manager.trigger(ctx, EventType.CHUNK_RERANK, cm);
        assertThat(result).isEqualTo(PluginErrorOrNone.NONE);
    }

    private PipelineRequest buildRequest(String query) {
        RetrieveRequest rr = RetrieveRequest.builder()
                .sessionId("s1").userId("u1").query(query)
                .knowledgeBaseIds(List.of("kb"))
                .build();
        return PipelineRequest.from(rr, new RagConfig());
    }

    /** 记录调用顺序的测试插件 */
    private static class RecordingPlugin implements Plugin {
        private final String name;
        private final EventType event;
        private final List<String> order;

        RecordingPlugin(String name, EventType event, List<String> order) {
            this.name = name;
            this.event = event;
            this.order = order;
        }

        @Override
        public PluginErrorOrNone onEvent(PipelineContext ctx, EventType eventType, ChatManage cm,
                                         java.util.function.Supplier<PluginErrorOrNone> next) {
            order.add(name);
            return next.get();
        }

        @Override
        public List<EventType> activationEvents() {
            return List.of(event);
        }
    }
}
