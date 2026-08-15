package io.invest.iagent.rag.retrieve.executor;

import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.PluginErrorOrNone;
import io.invest.iagent.rag.retrieve.dto.PluginException;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.plugins.Plugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 插件管理器：注册插件并按 EventType 构建责任链
 */
public class PluginsManager {
    private final Map<EventType, List<Plugin>> listeners = new EnumMap<>(EventType.class);
    private final Map<EventType, PluginChain> chains = new EnumMap<>(EventType.class);

    public PluginsManager(List<Plugin> plugins) {
        for (Plugin plugin : plugins) {
            for (EventType et : plugin.activationEvents()) {
                listeners.computeIfAbsent(et, k -> new ArrayList<>()).add(plugin);
            }
        }
        listeners.keySet().forEach(this::rebuildChain);
    }

    private void rebuildChain(EventType et) {
        List<Plugin> plugins = listeners.get(et);
        if (plugins == null || plugins.isEmpty()) {
            chains.put(et, (ctx, type, cm) -> Plugin.none());
            return;
        }
        // 倒序构建链（最后一个插件的 next 是 none）
        PluginChain chain = (ctx, type, cm) -> Plugin.none();
        for (int i = plugins.size() - 1; i >= 0; i--) {
            Plugin p = plugins.get(i);
            PluginChain prev = chain;
            chain = (ctx, type, cm) -> p.onEvent(ctx, type, cm, () -> {
                try {
                    return prev.onEvent(ctx, type, cm);
                } catch (PluginException e) {
                    // Supplier.get() 不允许抛 checked exception，包装后在 trigger 中解开
                    throw new PluginChainException(e);
                }
            });
        }
        chains.put(et, chain);
    }

    public PluginErrorOrNone trigger(PipelineContext ctx, EventType et, ChatManage cm) throws PluginException {
        PluginChain chain = chains.get(et);
        if (chain == null) return Plugin.none();
        try {
            return chain.onEvent(ctx, et, cm);
        } catch (PluginChainException e) {
            throw e.getCause();
        }
    }

    @FunctionalInterface
    private interface PluginChain {
        PluginErrorOrNone onEvent(PipelineContext ctx, EventType et, ChatManage cm) throws PluginException;
    }

    /** 用于在 Supplier lambda 中传递 PluginException 的包装异常 */
    private static class PluginChainException extends RuntimeException {
        PluginChainException(PluginException cause) {
            super(cause);
        }
        @Override
        public synchronized PluginException getCause() {
            return (PluginException) super.getCause();
        }
    }
}
