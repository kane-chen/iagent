package io.invest.iagent.financial.service;

import com.alibaba.fastjson2.JSON;
import io.invest.iagent.financial.config.FinancialProperties;
import io.invest.iagent.financial.ingest.FutuStatementIngestor;
import io.invest.iagent.financial.ingest.RagMetricExtractor;
import io.invest.iagent.financial.ingest.SegmentIngestor;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.repository.FinancialRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 财务数据采集编排：三大表（futu API）→ 入库 → 记录批次；
 * 随后 best-effort 接入 RAG 补充指标（API 缺失指标）与业务分部数据（本地财报文件解析）。
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

    @Autowired(required = false)
    private SegmentIngestor segmentIngestor;

    /**
     * 采集结果摘要（供工具层展示）。
     */
    public record BuildResult(String ticker, boolean success, String message,
                              int periodCount, int metricCount, List<String> warnings) {}

    public void buildResult(List<MetricValueDO> values){
        repository.batchUpsertMetrics(values);
    }

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

            // 全量刷新：先清空该公司旧指标值，避免期间口径调整（如财年标签改自然年）后新旧数据并存
            repository.deleteMetricsByTicker(r.company().getTicker());

            List<MetricValueDO> values = r.values();
            repository.batchUpsertMetrics(values);

            List<String> coveredPeriodList = values.stream()
                    .map(MetricValueDO::getFiscalPeriod).distinct()
                    .sorted().collect(Collectors.toList());
            String coveredPeriods = String.join(",", coveredPeriodList);
            String status = r.errors().isEmpty() ? "SUCCESS" : "PARTIAL";
            repository.recordBatch(r.company().getTicker(), "FUTU_API", status,
                    coveredPeriods, JSON.toJSONString(r.errors()));

            List<String> warnings = new java.util.ArrayList<>(r.errors());
            String bareTicker = r.company().getTicker();

            // RAG 补充指标提取（API 缺失指标，best-effort，不影响主流程），按本次采集期间逐期提取
            if (ragMetricExtractor != null) {
                try {
                    RagMetricExtractor.RagExtractResult rag =
                            ragMetricExtractor.extract(bareTicker, coveredPeriodList);
                    warnings.addAll(rag.warnings());
                } catch (Exception e) {
                    log.warn("RAG 补充指标提取异常（忽略）: {}", e.getMessage());
                }
            }

            // 业务分部数据提取（参照 segment-financial-report skill，解析本地财报文件，best-effort）：
            // 财报文件来自 FinancialReportService 下载到 workspace/financial_reports 的产物，
            // 且 skill 存在该公司分部配置；未下载财报或无配置时提示跳过。
            // 传入本次三大表覆盖期间，仅解析对应财报文件（含上年同期对比表），避免全量扫描历史文件
            int segmentCount = 0;
            int segmentValueCount = 0;
            if (segmentIngestor != null) {
                try {
                    SegmentIngestor.SegmentResult seg = segmentIngestor.ingest(bareTicker, coveredPeriodList);
                    warnings.addAll(seg.warnings());
                    if (seg.extracted()) {
                        segmentCount = seg.segments();
                        segmentValueCount = seg.values();
                    }
                } catch (Exception e) {
                    log.warn("业务分部数据提取异常（忽略）: {}", e.getMessage());
                }
            }

            String msg = String.format("已采集 %s(%s) 三大表数据：%d 个期间、%d 条指标值，币种 %s",
                    bareTicker, r.company().getMarket(),
                    r.periodCount(), values.size(), r.company().getCurrency());
            if (segmentValueCount > 0) {
                msg += String.format("；业务分部 %d 个、%d 条分部指标", segmentCount, segmentValueCount);
            }
            return new BuildResult(bareTicker, true, msg,
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
