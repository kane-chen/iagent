package io.invest.iagent.utils;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 外部进程调用工具：启动子进程、读全 stdout/stderr、按超时兜底 kill、返回 {@link Result}。
 *
 * <p>专为 iagent 里"直接跑 skill 的 python 脚本、不经过大模型"这类调试/回归场景设计——
 * 用两条后台线程分别 drain stdout / stderr 避免管道满导致子进程阻塞；stdout/stderr
 * 同时透传到本进程的 {@code System.out}/{@code System.err} 以便 Maven test 日志能看到。
 */
public final class ProcessRunner {

    private ProcessRunner() {}

    /**
     * 一次子进程运行的结果。<br>
     * {@code exitCode}=-1 表示进程未正常结束（超时被强杀）。
     */
    @Getter
    @AllArgsConstructor
    @ToString
    public static class Result {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    /**
     * 启动 {@code cmd} 指向的子进程，等待至多 {@code timeoutSeconds} 秒完成，返回
     * exitCode + 完整 stdout/stderr。stdout/stderr 也会实时打印到本进程日志（前缀 [stdout]/[stderr]）。
     *
     * @param cmd            命令行（可执行文件 + 参数）
     * @param workDir        工作目录；null 表示继承父进程
     * @param timeoutSeconds 超时秒数；超时后子进程被 {@code destroyForcibly()} 且 exitCode=-1
     * @return {@link Result}
     * @throws IOException          子进程启动失败
     * @throws InterruptedException 等待期间被中断
     */
    public static Result run(List<String> cmd, Path workDir, int timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        pb.environment().put("PYTHONIOENCODING", "utf-8");

        Process process = pb.start();
        StreamDrainer outDrainer = new StreamDrainer(process.getInputStream(), "stdout");
        StreamDrainer errDrainer = new StreamDrainer(process.getErrorStream(), "stderr");
        outDrainer.start();
        errDrainer.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        int exitCode;
        if (!finished) {
            process.destroyForcibly();
            exitCode = -1;
        } else {
            exitCode = process.exitValue();
        }
        outDrainer.join(2000);
        errDrainer.join(2000);
        return new Result(exitCode, outDrainer.getCaptured(), errDrainer.getCaptured());
    }

    /**
     * 后台读一条流的守护线程。既把每一行透传到本进程 {@code System.out/System.err}
     * 便于实时观察，也累积到 buffer 里最终塞进 {@link Result}。
     */
    private static class StreamDrainer extends Thread {
        private final InputStream stream;
        private final String label;
        private final StringBuilder buffer = new StringBuilder();

        StreamDrainer(InputStream stream, String label) {
            super("process-" + label);
            this.stream = stream;
            this.label = label;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    ("stderr".equals(label) ? System.err : System.out)
                            .println("[" + label + "] " + line);
                    buffer.append(line).append(System.lineSeparator());
                }
            } catch (IOException e) {
                System.err.println("[" + label + "] read failed: " + e.getMessage());
            }
        }

        String getCaptured() {
            return buffer.toString();
        }
    }
}
