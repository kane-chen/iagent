package io.invest.iagent.financial.ingest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.financial.config.FinancialProperties;
import io.invest.iagent.financial.model.MetricSource;
import io.invest.iagent.financial.model.SegmentDO;
import io.invest.iagent.financial.model.SegmentValueDO;
import io.invest.iagent.financial.repository.FinancialRepository;
import io.invest.iagent.utils.ProcessRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分部数据采集器：调用 segment-financial-report skill 的 extract_segments.py（本地财报文件解析，
 * 不联网），将扁平 JSON（segment × metric × period）入库 fin_segment / fin_segment_value。
 *
 * <p>前置条件：该公司财报已通过 futu-filing 下载到 workspace/portfolio/&lt;ticker&gt;/filings/，
 * 且 skill 的 config/extraction/&lt;TICKER&gt;.json 存在；否则脚本退出码 2，本采集器返回提示。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class SegmentIngestor {

    private static final String SCRIPT_REL = "skills/segment-financial-report/scripts/extract_segments.py";
    /** 引擎期间标签 2025FY / 2025Q1 / 2025H1 */
    private static final Pattern PERIOD_RE = Pattern.compile("(\\d{4})(FY|Q[1-4]|H[12])");
    /** 引擎指标编码 → 标准指标编码（其余编码与 catalog 一致） */
    private static final Map<String, String> METRIC_CODE_MAP = Map.of(
            "RD_EXPENSES", "RD_EXPENSE");

    @Autowired
    private Path workspace;

    @Autowired
    private FinancialProperties properties;

    @Autowired
    private FinancialRepository repository;

    /**
     * 采集结果。
     */
    public record SegmentResult(boolean extracted, int segments, int values, List<String> warnings) {}

    /**
     * 执行分部数据提取与入库。
     *
     * @param ticker 裸 ticker（BABA / 00700，不带市场前缀）
     */
    public SegmentResult ingest(String ticker) {
        List<String> warnings = new ArrayList<>();
        Path output = workspace.resolve("temp").resolve(ticker + "_segments.json");
        Path script = workspace.resolve(SCRIPT_REL);
        List<String> cmd = List.of(
                properties.getPythonExecutable(), script.toAbsolutePath().toString(),
                "--ticker", ticker,
                "--workspace", workspace.toAbsolutePath().toString(),
                "--output", output.toAbsolutePath().toString());
        ProcessRunner.Result result;
        try {
            result = ProcessRunner.run(cmd, null, properties.getSegmentTimeoutSeconds());
        } catch (Exception e) {
            warnings.add("分部数据提取执行失败: " + e.getMessage());
            return new SegmentResult(false, 0, 0, warnings);
        }

        // 退出码 2：无可处理财报 / 无公司配置（脚本 stderr 已含中文提示）
        if (result.getExitCode() == 2 || !Files.isRegularFile(output)) {
            String hint = lastLines(result.getStderr(), 400);
            warnings.add("未生成分部数据（可能财报未下载或暂无该公司分部配置）。" + hint);
            log.info("Segment ingest skipped for {}: rc={}", ticker, result.getExitCode());
            return new SegmentResult(false, 0, 0, warnings);
        }
        if (!result.isSuccess()) {
            warnings.add("分部数据提取脚本失败(rc=" + result.getExitCode() + "): "
                    + lastLines(result.getStderr(), 400));
            return new SegmentResult(false, 0, 0, warnings);
        }

        try {
            JSONArray records = JSON.parseArray(Files.readString(output, StandardCharsets.UTF_8));
            // segmentCode -> 定义（保留首次出现顺序 = 引擎树序）
            Map<String, SegmentDO> segmentMap = new LinkedHashMap<>();
            List<SegmentValueDO> values = new ArrayList<>();

            for (int i = 0; i < records.size(); i++) {
                JSONObject rec = records.getJSONObject(i);
                if (rec.getBooleanValue("__meta__", false)) {
                    continue;
                }
                String segCode = rec.getString("segmentCode");
                String segName = rec.getString("segmentName");
                String period = canonicalPeriod(rec.getString("period"));
                String metricCode = METRIC_CODE_MAP.getOrDefault(rec.getString("metricCode"), rec.getString("metricCode"));
                BigDecimal value = rec.getBigDecimal("value");
                if (segCode == null || period == null || metricCode == null) {
                    continue;
                }
                segmentMap.computeIfAbsent(segCode, k -> SegmentDO.builder()
                        .ticker(ticker)
                        .segmentCode(segCode)
                        .segmentName(segName)
                        .parentCode(rec.getString("parentSegmentCode"))
                        .level(rec.getIntValue("level", 1))
                        .sortOrder(segmentMap.size())
                        .build());

                values.add(SegmentValueDO.builder()
                        .ticker(ticker)
                        .fiscalPeriod(period)
                        .segmentCode(segCode)
                        .metricCode(metricCode)
                        .value(value)
                        .currency(rec.getString("currency"))
                        .unit("million")
                        .source(MetricSource.SEGMENT_PARSE.name())
                        .confidence(rec.getInteger("confidenceScore"))
                        .build());
            }

            if (values.isEmpty()) {
                warnings.add("分部脚本未解析出有效数据。");
                return new SegmentResult(false, 0, 0, warnings);
            }

            fillYoY(values);
            repository.batchUpsertSegments(new ArrayList<>(segmentMap.values()));
            repository.batchUpsertSegmentValues(values);
            repository.recordBatch(ticker, "SEGMENT_PARSE", "SUCCESS",
                    values.stream().map(SegmentValueDO::getFiscalPeriod).distinct().sorted()
                            .reduce((a, b) -> a + "," + b).orElse(""),
                    "segments=" + segmentMap.size() + ", values=" + values.size());

            log.info("Segment ingest done: ticker={}, segments={}, values={}",
                    ticker, segmentMap.size(), values.size());
            return new SegmentResult(true, segmentMap.size(), values.size(), warnings);
        } catch (Exception e) {
            log.error("分部数据解析失败: ticker={}", ticker, e);
            warnings.add("分部数据解析失败: " + e.getMessage());
            return new SegmentResult(false, 0, 0, warnings);
        }
    }

    /** "2025FY" → "FY2025"；"2025Q1"/"2025H1" 保持；无法识别返回 null。 */
    public static String canonicalPeriod(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = PERIOD_RE.matcher(raw.trim().toUpperCase());
        if (!m.find()) {
            return null;
        }
        String year = m.group(1);
        String tag = m.group(2);
        return "FY".equals(tag) ? "FY" + year : year + tag;
    }

    /**
     * 按 (分部, 指标) 分组计算同比：FY 对比上一 FY，Qn 对比去年同 Q，Hn 同理。
     */
    private void fillYoY(List<SegmentValueDO> values) {
        // key: segmentCode|metricCode -> (periodKey -> row)
        Map<String, Map<String, SegmentValueDO>> byKey = new HashMap<>();
        for (SegmentValueDO v : values) {
            if (v.getValue() == null) {
                continue;
            }
            byKey.computeIfAbsent(v.getSegmentCode() + "|" + v.getMetricCode(), k -> new TreeMap<>())
                    .put(v.getFiscalPeriod(), v);
        }
        for (Map<String, SegmentValueDO> periodMap : byKey.values()) {
            for (SegmentValueDO v : periodMap.values()) {
                String year;
                String suffix;
                if (v.getFiscalPeriod().startsWith("FY")) {
                    year = v.getFiscalPeriod().substring(2);
                    suffix = "FY";
                } else if (v.getFiscalPeriod().length() >= 5) {
                    year = v.getFiscalPeriod().substring(0, 4);
                    suffix = v.getFiscalPeriod().substring(4);
                } else {
                    continue;
                }
                String priorLabel = "FY".equals(suffix)
                        ? "FY" + (Integer.parseInt(year) - 1)
                        : (Integer.parseInt(year) - 1) + suffix;
                SegmentValueDO prior = periodMap.get(priorLabel);
                if (prior != null && prior.getValue() != null
                        && prior.getValue().compareTo(BigDecimal.ZERO) != 0) {
                    v.setYoy(v.getValue().subtract(prior.getValue())
                            .multiply(BigDecimal.valueOf(100))
                            .divide(prior.getValue().abs(), 2, RoundingMode.HALF_UP));
                }
            }
        }
    }

    private static String lastLines(String s, int max) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(trimmed.length() - max);
    }
}
