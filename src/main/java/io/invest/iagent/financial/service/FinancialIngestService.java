package io.invest.iagent.financial.service;

import com.alibaba.fastjson2.JSON;
import io.invest.iagent.financial.config.FinancialProperties;
import io.invest.iagent.financial.ingest.FutuStatementIngestor;
import io.invest.iagent.financial.ingest.RagMetricExtractor;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.repository.FinancialRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 财务数据采集编排：三大表（futu API）→ 入库 → 记录批次。
 * 分部数据、RAG 补充指标由各自的 Ingestor 在后续步骤接入（见任务 5/6）。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class FinancialIngestService {

    @Autowired
    private FutuStatementIngestor futuIngestor;

    @Autowired
    private FinancialRepository repository;

    @Autowired
    private FinancialProperties properties;

    @Autowired(required = false)
    private RagMetricExtractor ragMetricExtractor;

    /**
     * 采集结果摘要（供工具层展示）。
     */
    public record BuildResult(String ticker, boolean success, String message,
                              int periodCount, int metricCount, List<String> warnings) {}

    /**
     * 采集指定公司的三大表数据并入库。
     *
     * @param ticker  裸 ticker（00700 / BABA / 600519）
     * @param periods 拉取期数；null 用默认配置
     */
    public BuildResult build(String ticker, Integer periods) {
        int num = periods != null && periods > 0 ? periods : properties.getDefaultPeriods();
        try {
            FutuStatementIngestor.IngestResult r = futuIngestor.ingest(ticker, num);
            repository.upsertCompany(r.company());

            List<MetricValueDO> values = r.values();
            repository.batchUpsertMetrics(values);

            String coveredPeriods = values.stream()
                    .map(MetricValueDO::getFiscalPeriod).distinct()
                    .sorted().collect(Collectors.joining(","));
            String status = r.errors().isEmpty() ? "SUCCESS" : "PARTIAL";
            repository.recordBatch(r.company().getTicker(), "FUTU_API", status,
                    coveredPeriods, JSON.toJSONString(r.errors()));

            List<String> warnings = new java.util.ArrayList<>(r.errors());
            // RAG 补充指标提取（API 缺失指标，best-effort，不影响主流程）
            if (ragMetricExtractor != null) {
                try {
                    RagMetricExtractor.RagExtractResult rag = ragMetricExtractor.extract(r.company().getTicker());
                    warnings.addAll(rag.warnings());
                } catch (Exception e) {
                    log.warn("RAG 补充指标提取异常（忽略）: {}", e.getMessage());
                }
            }

            String msg = String.format("已采集 %s(%s) 三大表数据：%d 个期间、%d 条指标值，币种 %s",
                    r.company().getTicker(), r.company().getMarket(),
                    r.periodCount(), values.size(), r.company().getCurrency());
            return new BuildResult(r.company().getTicker(), true, msg,
                    r.periodCount(), values.size(), warnings);
        } catch (Exception e) {
            log.error("财务数据采集失败: ticker={}", ticker, e);
            String warning = "采集失败: " + e.getMessage();
            try {
                repository.recordBatch(ticker, "FUTU_API", "FAILED", null, warning);
            } catch (Exception ignore) {
                // 批次记录失败不影响主流程返回
            }
            return new BuildResult(ticker, false, warning, 0, 0, List.of(warning));
        }
    }

    /**
     * 查询时自动补采：库中该公司期间数不足时触发一次采集。
     *
     * @return true 表示触发了采集
     */
    public boolean backfillIfNeeded(String ticker) {
        try {
            List<String> existing = repository.findMetricPeriods(ticker);
            if (existing.size() >= properties.getMinPeriodsForQuery()) {
                return false;
            }
            log.info("财务数据期间不足（{} 个），自动补采: ticker={}", existing.size(), ticker);
            BuildResult r = build(ticker, properties.getDefaultPeriods());
            return r.success();
        } catch (Exception e) {
            log.warn("自动补采失败: ticker={}, {}", ticker, e.getMessage());
            return false;
        }
    }
}
