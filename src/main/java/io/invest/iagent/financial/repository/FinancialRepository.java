package io.invest.iagent.financial.repository;

import io.invest.iagent.financial.model.CompanyDO;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.model.SegmentDO;
import io.invest.iagent.financial.model.SegmentValueDO;

import java.util.List;

/**
 * 财务数据仓储：指标值 / 分部 / 公司元数据 / 采集批次。
 */
public interface FinancialRepository {

    // ---------- 写入（幂等 upsert） ----------

    void upsertCompany(CompanyDO company);

    void batchUpsertMetrics(List<MetricValueDO> values);

    void batchUpsertSegments(List<SegmentDO> segments);

    void batchUpsertSegmentValues(List<SegmentValueDO> values);

    void recordBatch(String ticker, String source, String status, String periods, String report);

    // ---------- 查询 ----------

    /**
     * 查询指标值。同一 (期间, 指标) 存在多来源时按 FUTU_API > DERIVED > RAG 取最优来源。
     *
     * @param ticker      股票代码
     * @param metricCodes 标准指标编码（null/空 = 全部）
     * @param periods     规范化期间（null/空 = 全部）
     */
    List<MetricValueDO> queryMetrics(String ticker, List<String> metricCodes, List<String> periods);

    /** 该公司已入库的期间（按 FUTU_API 来源 distinct），按期间升序 */
    List<String> findMetricPeriods(String ticker);

    /** 公司信息，不存在返回 null */
    CompanyDO findCompany(String ticker);

    /** 分部目录 */
    List<SegmentDO> findSegments(String ticker);

    /** 分部指标值 */
    List<SegmentValueDO> querySegmentValues(String ticker, List<String> periods);
}
