package io.invest.iagent.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 测试用 Python 解释器解析：找到一个「确实装好第三方依赖」的解释器绝对路径。
 *
 * <p>背景：Windows 上 {@code python}/{@code python3} 在不同环境（IDE / Git Bash / cmd）
 * 的 PATH 顺序不一致，且 {@code %LOCALAPPDATA%\Microsoft\WindowsApps} 下的
 * {@code python.exe/python3.exe} 是 Python Manager（pymanager）/应用商店启动器，
 * 会导向一个缺依赖的 Python（常见 "requests not installed / No module named idna"）；
 * 而依赖（requests 等）实际装在 anaconda 的解释器里。
 *
 * <p>策略：枚举候选解释器（系统属性/环境变量覆盖优先，其次 CONDA_PREFIX、{@code where}
 * 结果、常见 anaconda 安装路径，全程排除 WindowsApps 启动器），逐个实际运行
 * {@code -c "import requests"} 验证，取第一个通过的绝对路径，结果缓存。
 * 可用 {@code -Dpython.exec=...} 或环境变量 {@code PYTHON_EXEC} 直接指定（信任、不探测）。
 */
public final class PythonCmd {

    /** 探测时验证可导入的模块（该环境装好依赖的标志）。 */
    private static final String PROBE_MODULE = "requests";

    private static volatile String cached;

    private PythonCmd() {
    }

    /** 返回可用的 Python 可执行文件路径/命令。 */
    public static String executable() {
        String result = cached;
        if (result == null) {
            synchronized (PythonCmd.class) {
                result = cached;
                if (result == null) {
                    result = resolve();
                    cached = result;
                    System.out.println("[PythonCmd] using python: " + result);
                }
            }
        }
        return result;
    }

    private static String resolve() {
        boolean win = isWindows();

        // 1) 显式覆盖：信任，直接返回
        String override = System.getProperty("python.exec");
        if (isBlank(override)) {
            override = System.getenv("PYTHON_EXEC");
        }
        if (!isBlank(override)) {
            return override.trim();
        }

        // 2) 候选解释器（去重、保序）
        Set<String> candidates = new LinkedHashSet<>();
        if (win) {
            String conda = System.getenv("CONDA_PREFIX");
            if (!isBlank(conda)) {
                candidates.add(new File(conda, "python.exe").getAbsolutePath());
            }
            addWhere(candidates, "python");
            addWhere(candidates, "python3");
            String home = System.getProperty("user.home");
            String local = System.getenv("LOCALAPPDATA");
            String prog = System.getenv("ProgramFiles");
            String progData = System.getenv("ProgramData");
            addIfSet(candidates, local, "anaconda3\\python.exe");
            addIfSet(candidates, local, "miniconda3\\python.exe");
            addIfSet(candidates, prog, "anaconda3\\python.exe");
            addIfSet(candidates, prog, "miniconda3\\python.exe");
            addIfSet(candidates, progData, "anaconda3\\python.exe");
            addIfSet(candidates, progData, "miniconda3\\python.exe");
            addIfSet(candidates, home, "anaconda3\\python.exe");
            addIfSet(candidates, home, "miniconda3\\python.exe");
            // 本机 anaconda 安装位置
            candidates.add("D:\\dev\\component\\anaconda3\\python.exe");
        } else {
            candidates.add("python3");
            candidates.add("python");
        }

        // 3) 逐个探测：排除 WindowsApps 启动器，验证可导入依赖
        for (String c : candidates) {
            if (isWindowsApps(c)) {
                continue;
            }
            if (canImport(c)) {
                return c;
            }
        }

        // 4) 兜底：返回平台默认命令
        return win ? "python" : "python3";
    }

    /** 运行 {@code where <name>}（Windows）把可解析的解释器路径加入候选。 */
    private static void addWhere(Set<String> candidates, String name) {
        try {
            Process p = new ProcessBuilder("cmd", "/c", "where", name).redirectErrorStream(true).start();
            boolean done = p.waitFor(8, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return;
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && new File(line).isFile()) {
                        candidates.add(line);
                    }
                }
            }
        } catch (Exception ignore) {
            // where 不可用则跳过
        }
    }

    private static void addIfSet(Set<String> candidates, String base, String rel) {
        if (!isBlank(base)) {
            candidates.add(new File(base, rel).getAbsolutePath());
        }
    }

    /** 实际运行候选解释器，验证能导入目标模块。 */
    private static boolean canImport(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd, "-c", "import " + PROBE_MODULE);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** WindowsApps 下的是 Python Manager / 应用商店启动器，跳过。 */
    private static boolean isWindowsApps(String path) {
        return path != null
                && path.toLowerCase().replace('/', '\\').contains("\\windowsapps\\");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
