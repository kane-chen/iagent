package io.invest.iagent.utils;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Python 解释器解析：跨平台返回一个「确实能启动」的解释器命令。
 *
 * <p>背景：配置默认 {@code python}，但 macOS / Linux 上通常只有 {@code python3}，
 * 直接 {@code new ProcessBuilder("python", ...).start()} 会抛
 * "Cannot run program \"python\": error=2 (No such file or directory)"；
 * Windows 上则一般是 {@code python}。这里按「配置值优先，其次 python3 / python」
 * 逐个实际启动探测（{@code -c "import sys"}），取第一个能正常退出的，结果缓存。
 */
public final class PythonResolver {

    private static volatile String cached;

    private PythonResolver() {
    }

    /**
     * 返回可用的 Python 解释器命令。
     *
     * @param configured 配置的解释器（{@code app.financial.python-executable}），可为 null
     */
    public static String resolve(String configured) {
        String result = cached;
        if (result == null) {
            synchronized (PythonResolver.class) {
                result = cached;
                if (result == null) {
                    result = doResolve(configured);
                    cached = result;
                }
            }
        }
        return result;
    }

    private static String doResolve(String configured) {
        // 候选去重保序：配置值优先，其次按平台习惯给 python3 / python
        Set<String> candidates = new LinkedHashSet<>();
        if (configured != null && !configured.isBlank()) {
            candidates.add(configured.trim());
        }
        candidates.add("python3");
        candidates.add("python");

        for (String c : candidates) {
            if (canStart(c)) {
                return c;
            }
        }
        // 全部探测失败：回退配置值（保留原报错路径，便于暴露问题）
        return configured != null && !configured.isBlank() ? configured.trim() : "python";
    }

    /** 实际启动候选解释器，能正常退出即视为可用。 */
    private static boolean canStart(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "-c", "import sys")
                    .redirectErrorStream(true).start();
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            // 命令不存在（IOException）等：该候选不可用
            return false;
        }
    }
}
