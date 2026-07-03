/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */

package io.invest.iagent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.model.SegmentMetricDTO;
import io.invest.iagent.service.extraction.service.FinancialExtractionService;
import io.invest.iagent.service.extraction.service.SegmentMetricUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 分部财务数据提取 CLI —— 独立 main，不启动 Spring 上下文，最小依赖。
 * <p>
 * 该入口是 {@code segment-financial-report} skill 通过 shell 调用 Java 提取引擎的桥梁。
 * 内部完全复用 {@link FinancialExtractionService}（保留 HtmlLayoutHandler /
 * PdfLayoutHandler 等策略模式与公司配置隔离），只是把参数从 {@code @Tool} 换成 argv、
 * 把返回值从 Java 对象换成 stdout JSON。
 * <p>
 * 用法：
 * <pre>
 *   java -cp target/iagent-1.1.0.jar io.invest.iagent.cli.SegmentExtractionCli \
 *        --ticker BABA \
 *        --workspace D:/dev/codes/github/iagent/workspace \
 *        [--output segments.json] \
 *        [--flat]
 * </pre>
 * <p>
 * 输出：默认写 stdout；若指定 {@code --output} 则写文件。默认输出树状 {@link Segment}；
 * 加 {@code --flat} 输出扁平化后的 {@link SegmentMetricDTO} 列表（Excel 渲染器要这个格式）。
 * <p>
 * 退出码：{@code 0} 成功，{@code 1} 参数错误，{@code 2} 无候选财报文件，{@code 3} 提取失败。
 */
public final class SegmentExtractionCli {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SegmentExtractionCli() {}

    public static void main(String[] args) {
        try {
            Args parsed = Args.parse(args);
            int code = run(parsed);
            System.exit(code);
        } catch (IllegalArgumentException e) {
            System.err.println("[cli] 参数错误: " + e.getMessage());
            System.err.println(usage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("[cli] 提取失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(3);
        }
    }

    private static int run(Args args) throws IOException {
        FinancialExtractionService service = new FinancialExtractionService(args.ticker, args.workspace);
        List<Segment> segments = service.extractSegments(args.ticker, args.fiscalYearStart, args.fiscalYearEnd);

        if (segments == null || segments.isEmpty()) {
            System.err.println("[cli] 未找到 " + args.ticker + " 的分部财务数据（workspace=" + args.workspace + "）");
            return 2;
        }

        Object payload = args.flat
                ? SegmentMetricUtil.flattenAndSort(segments)
                : segments;

        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        if (args.output != null) {
            Files.createDirectories(args.output.getParent() == null ? args.output.toAbsolutePath().getParent() : args.output.getParent());
            Files.writeString(args.output, json);
            System.err.println("[cli] 写出 " + segments.size() + " 个顶层分部到 " + args.output);
        } else {
            System.out.println(json);
        }
        return 0;
    }

    private static String usage() {
        return """
                Usage:
                  java -cp iagent-<ver>.jar io.invest.iagent.cli.SegmentExtractionCli \\
                       --ticker <TICKER> \\
                       --workspace <WORKSPACE_ROOT> \\
                       [--fiscal-year-start <YYYY>] [--fiscal-year-end <YYYY>] \\
                       [--output <FILE>] [--flat]

                示例：
                  java -cp iagent-1.1.0.jar io.invest.iagent.cli.SegmentExtractionCli \\
                       --ticker BABA --workspace ./workspace --flat --output out.json
                """;
    }

    // -----------------------------------------------------------------
    // argv parsing
    // -----------------------------------------------------------------

    private static final class Args {
        String ticker;
        Path workspace;
        String fiscalYearStart;
        String fiscalYearEnd;
        Path output;
        boolean flat;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String k = argv[i];
                switch (k) {
                    case "--ticker" -> a.ticker = requireValue(argv, ++i, k);
                    case "--workspace" -> a.workspace = Paths.get(requireValue(argv, ++i, k));
                    case "--fiscal-year-start" -> a.fiscalYearStart = requireValue(argv, ++i, k);
                    case "--fiscal-year-end" -> a.fiscalYearEnd = requireValue(argv, ++i, k);
                    case "--output" -> a.output = Paths.get(requireValue(argv, ++i, k));
                    case "--flat" -> a.flat = true;
                    default -> throw new IllegalArgumentException("未知参数: " + k);
                }
            }
            if (a.ticker == null || a.ticker.isBlank()) {
                throw new IllegalArgumentException("--ticker 必填");
            }
            if (a.workspace == null) {
                throw new IllegalArgumentException("--workspace 必填");
            }
            return a;
        }

        private static String requireValue(String[] argv, int idx, String key) {
            if (idx >= argv.length) {
                throw new IllegalArgumentException(key + " 缺少参数值");
            }
            return argv[idx];
        }
    }
}
