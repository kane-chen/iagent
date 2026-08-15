package io.invest.iagent.rag.retrieve.dto;

// 插件执行结果：None（继续）或Error（中断）
public class PluginErrorOrNone {
    public static final PluginErrorOrNone NONE = new PluginErrorOrNone(null);
    public final PluginException error;
    public PluginErrorOrNone(PluginException error) {
        this.error = error;
    }
    public boolean hasError() {
        return error != null;
    }
}
