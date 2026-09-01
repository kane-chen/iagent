package io.invest.iagent.financial.service;

import io.invest.iagent.financial.config.FinancialProperties;
import io.invest.iagent.financial.ingest.FutuStatementIngestor;
import io.invest.iagent.financial.ingest.SegmentIngestor;
import io.invest.iagent.financial.model.CompanyDO;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.repository.FinancialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FinancialIngestService#build} 编排单元测试：三大表采集后接入业务分部数据提取（best-effort）。
 */
class FinancialIngestBuildTest {

    private static final String TICKER = "BABA";

    private FutuStatementIngestor futuIngestor;
    private FinancialRepository repository;
    private SegmentIngestor segmentIngestor;
    private FinancialIngestService service;

    @BeforeEach
    void setUp() {
        futuIngestor = mock(FutuStatementIngestor.class);
        repository = mock(FinancialRepository.class);
        FinancialProperties properties = mock(FinancialProperties.class);
        when(properties.getDefaultPeriods()).thenReturn(25);
        segmentIngestor = mock(SegmentIngestor.class);

        service = new FinancialIngestService();
        ReflectionTestUtils.setField(service, "futuIngestor", futuIngestor);
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "properties", properties);
        // ragMetricExtractor 留空（null），聚焦分部步骤
        ReflectionTestUtils.setField(service, "segmentIngestor", segmentIngestor);
    }

    private FutuStatementIngestor.IngestResult futuResult() {
        CompanyDO company = CompanyDO.builder().ticker(TICKER).market("US").currency("CNY").build();
        MetricValueDO v = MetricValueDO.builder().ticker(TICKER).fiscalPeriod("2026Q1")
                .metricCode("REVENUE").value(new BigDecimal("1000")).build();
        return new FutuStatementIngestor.IngestResult(company, List.of(v), List.of(), 1);
    }

    /** build 三大表成功后调用分部提取并入库，消息含分部计数。 */
    @Test
    void build_invokesSegmentIngest_andReportsCounts() throws Exception {
        when(futuIngestor.ingest(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(futuResult());
        when(segmentIngestor.ingest(TICKER))
                .thenReturn(new SegmentIngestor.SegmentResult(true, 3, 30, List.of()));

        FinancialIngestService.BuildResult result = service.build(TICKER, 25);

        assertThat(result.success()).isTrue();
        verify(segmentIngestor, times(1)).ingest(TICKER);
        // 全量刷新：入库前先清空该公司旧指标值（避免新旧期间标签并存）
        verify(repository, times(1)).deleteMetricsByTicker(TICKER);
        assertThat(result.message()).contains("业务分部 3 个、30 条分部指标");
    }

    /** 分部数据未生成（财报未下载/无配置）时仅告警，三大表采集仍成功，消息不含分部计数。 */
    @Test
    void build_segmentSkipped_warnsButSucceeds() throws Exception {
        when(futuIngestor.ingest(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(futuResult());
        when(segmentIngestor.ingest(TICKER))
                .thenReturn(new SegmentIngestor.SegmentResult(false, 0, 0,
                        List.of("未生成分部数据（可能财报未下载或暂无该公司分部配置）。")));

        FinancialIngestService.BuildResult result = service.build(TICKER, 25);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).doesNotContain("业务分部");
        assertThat(result.warnings()).anyMatch(w -> w.contains("未生成分部数据"));
    }

    /** 分部提取抛异常不影响三大表主流程。 */
    @Test
    void build_segmentThrows_stillSucceeds() throws Exception {
        when(futuIngestor.ingest(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(futuResult());
        when(segmentIngestor.ingest(TICKER)).thenThrow(new RuntimeException("python boom"));

        FinancialIngestService.BuildResult result = service.build(TICKER, 25);

        assertThat(result.success()).isTrue();
        verify(repository, times(1)).batchUpsertMetrics(org.mockito.ArgumentMatchers.anyList());
    }

    /** 未注入分部采集器（bean 缺失）时不调用、不报错。 */
    @Test
    void build_withoutSegmentBean_skips() throws Exception {
        ReflectionTestUtils.setField(service, "segmentIngestor", null);
        when(futuIngestor.ingest(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(futuResult());

        FinancialIngestService.BuildResult result = service.build(TICKER, 25);

        assertThat(result.success()).isTrue();
        verify(segmentIngestor, never()).ingest(anyString());
    }
}
