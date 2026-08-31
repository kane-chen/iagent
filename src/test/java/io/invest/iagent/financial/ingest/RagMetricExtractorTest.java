package io.invest.iagent.financial.ingest;

import io.invest.iagent.financial.config.FinancialProperties;
import io.invest.iagent.financial.config.RagExtraMetric;
import io.invest.iagent.financial.model.MetricCatalog;
import io.invest.iagent.financial.model.MetricSource;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.model.PeriodType;
import io.invest.iagent.financial.repository.FinancialRepository;
import io.invest.iagent.rag.filing.FilingQaService;
import io.invest.iagent.rag.filing.model.FilingAnswer;
import io.invest.iagent.rag.filing.model.FilingChunk;
import io.invest.iagent.rag.filing.retrieve.FilingTagKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RagMetricExtractor} 单元测试：期间列表逐期提取、跳过逻辑与置信度过滤。
 */
class RagMetricExtractorTest {

    private static final String TICKER = "00700";

    private FinancialRepository repository;
    private FinancialProperties properties;
    private FilingQaService filingQaService;
    private RagMetricExtractor extractor;

    private final RagExtraMetric sbc = extraMetric("SBC", 60);

    @BeforeEach
    void setUp() {
        repository = mock(FinancialRepository.class);
        properties = mock(FinancialProperties.class);
        MetricCatalog metricCatalog = mock(MetricCatalog.class);
        filingQaService = mock(FilingQaService.class);

        when(properties.isRagExtractEnabled()).thenReturn(true);
        when(properties.getRagTopK()).thenReturn(5);
        // 默认库中无任何已有值 → 每期都需要提取
        when(repository.queryMetrics(anyString(), anyList(), anyList())).thenReturn(List.of());

        extractor = new RagMetricExtractor();
        ReflectionTestUtils.setField(extractor, "repository", repository);
        ReflectionTestUtils.setField(extractor, "properties", properties);
        ReflectionTestUtils.setField(extractor, "metricCatalog", metricCatalog);
        ReflectionTestUtils.setField(extractor, "extraMetrics", List.of(sbc));
        ReflectionTestUtils.setField(extractor, "filingQaService", filingQaService);
    }

    /** 逐期提问：期间列表中每个期间各发起一次 ask，入库行的期间口径与期间匹配。 */
    @Test
    void extract_iteratesPeriods_oneAskPerPeriod() {
        when(filingQaService.ask(anyString(), eq(TICKER), anyString(), anyInt()))
                .thenAnswer(inv -> answerWithSbc(inv.getArgument(2), 90));

        RagMetricExtractor.RagExtractResult result =
                extractor.extract(TICKER, List.of("2025Q1", "2025Q2", "FY2025"));

        // 一个周期一次 ask，防止知识库上下文过大
        verify(filingQaService, times(1)).ask(anyString(), eq(TICKER), eq("2025Q1"), eq(5));
        verify(filingQaService, times(1)).ask(anyString(), eq(TICKER), eq("2025Q2"), eq(5));
        verify(filingQaService, times(1)).ask(anyString(), eq(TICKER), eq("FY2025"), eq(5));
        assertThat(result.extracted()).isEqualTo(3);
        assertThat(result.warnings()).isEmpty();

        ArgumentCaptor<List<MetricValueDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(3)).batchUpsertMetrics(captor.capture());
        List<MetricValueDO> all = captor.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(all).hasSize(3);
        assertThat(all).anySatisfy(v -> {
            assertThat(v.getFiscalPeriod()).isEqualTo("2025Q1");
            assertThat(v.getPeriodType()).isEqualTo(PeriodType.SINGLE_Q.name());
        });
        assertThat(all).anySatisfy(v -> {
            assertThat(v.getFiscalPeriod()).isEqualTo("2025Q2");
            assertThat(v.getPeriodType()).isEqualTo(PeriodType.SINGLE_Q.name());
        });
        assertThat(all).anySatisfy(v -> {
            assertThat(v.getFiscalPeriod()).isEqualTo("FY2025");
            assertThat(v.getPeriodType()).isEqualTo(PeriodType.FY.name());
        });
        assertThat(all).allSatisfy(v -> {
            assertThat(v.getSource()).isEqualTo(MetricSource.RAG.name());
            assertThat(v.getChunkId()).isEqualTo("chunk-1");
            assertThat(v.getDocumentId()).isEqualTo("doc-1");
        });
    }

    /** H1 中报期间按累计口径入库。 */
    @Test
    void extract_h1Period_storedAsCumulative() {
        when(filingQaService.ask(anyString(), eq(TICKER), anyString(), anyInt()))
                .thenAnswer(inv -> answerWithSbc(inv.getArgument(2), 90));

        extractor.extract(TICKER, List.of("2025H1"));

        ArgumentCaptor<List<MetricValueDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).batchUpsertMetrics(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        MetricValueDO row = captor.getValue().get(0);
        assertThat(row.getFiscalPeriod()).isEqualTo("2025H1");
        assertThat(row.getPeriodType()).isEqualTo(PeriodType.CUMULATIVE.name());
    }

    /** 期间列表为空/null 时直接跳过，不发起检索。 */
    @Test
    void extract_emptyPeriods_skips() {
        RagMetricExtractor.RagExtractResult r1 = extractor.extract(TICKER, List.of());
        RagMetricExtractor.RagExtractResult r2 = extractor.extract(TICKER, null);

        assertThat(r1.extracted()).isZero();
        assertThat(r2.extracted()).isZero();
        assertThat(r1.warnings()).anyMatch(w -> w.contains("无有效期间"));
        verify(filingQaService, never()).ask(anyString(), anyString(), anyString(), anyInt());
    }

    /** 无法识别的期间告警跳过，合法期间正常提取。 */
    @Test
    void extract_invalidPeriod_warnedAndSkipped() {
        when(filingQaService.ask(anyString(), eq(TICKER), anyString(), anyInt()))
                .thenAnswer(inv -> answerWithSbc(inv.getArgument(2), 90));

        RagMetricExtractor.RagExtractResult result =
                extractor.extract(TICKER, List.of("garbage", " FY2025 "));

        assertThat(result.warnings()).anyMatch(w -> w.contains("无法识别的期间"));
        verify(filingQaService, times(1)).ask(anyString(), eq(TICKER), eq("FY2025"), anyInt());
        assertThat(result.extracted()).isEqualTo(1);
    }

    /** 所有期间都检索不到片段时给出知识库未构建提示，且不入库。 */
    @Test
    void extract_noChunksAnyPeriod_warnsKbMissing() {
        FilingAnswer empty = new FilingAnswer();
        empty.setChunks(List.of());
        when(filingQaService.ask(anyString(), eq(TICKER), anyString(), anyInt())).thenReturn(empty);

        RagMetricExtractor.RagExtractResult result =
                extractor.extract(TICKER, List.of("2025Q1", "FY2025"));

        assertThat(result.extracted()).isZero();
        assertThat(result.warnings()).anyMatch(w -> w.contains("知识库未检索到片段"));
        verify(repository, never()).batchUpsertMetrics(anyList());
    }

    /** 指标已有高优先级来源值时，该期间跳过提问。 */
    @Test
    void extract_existingHighPriorityValue_skipsAsk() {
        when(repository.queryMetrics(anyString(), anyList(), anyList()))
                .thenReturn(List.of(MetricValueDO.builder()
                        .ticker(TICKER).fiscalPeriod("FY2025").metricCode("SBC")
                        .value(BigDecimal.valueOf(100)).source(MetricSource.FUTU_API.name())
                        .build()));

        RagMetricExtractor.RagExtractResult result = extractor.extract(TICKER, List.of("FY2025"));

        assertThat(result.extracted()).isZero();
        verify(filingQaService, never()).ask(anyString(), anyString(), anyString(), anyInt());
        verify(repository, never()).batchUpsertMetrics(anyList());
    }

    /** 低于置信度阈值的提取结果不入库。 */
    @Test
    void extract_lowConfidence_notUpserted() {
        when(filingQaService.ask(anyString(), eq(TICKER), anyString(), anyInt()))
                .thenAnswer(inv -> answerWithSbc(inv.getArgument(2), 30));

        RagMetricExtractor.RagExtractResult result = extractor.extract(TICKER, List.of("FY2025"));

        assertThat(result.extracted()).isZero();
        verify(repository, never()).batchUpsertMetrics(anyList());
    }

    /** RAG 提取开关关闭时直接返回，不发起检索。 */
    @Test
    void extract_disabled_returnsZero() {
        when(properties.isRagExtractEnabled()).thenReturn(false);

        RagMetricExtractor.RagExtractResult result = extractor.extract(TICKER, List.of("FY2025"));

        assertThat(result.extracted()).isZero();
        verify(filingQaService, never()).ask(anyString(), anyString(), anyString(), anyInt());
    }

    /** filing RAG 模块未启用（bean 缺失）时给出提示并跳过。 */
    @Test
    void extract_filingModuleMissing_warns() {
        ReflectionTestUtils.setField(extractor, "filingQaService", null);

        RagMetricExtractor.RagExtractResult result = extractor.extract(TICKER, List.of("FY2025"));

        assertThat(result.extracted()).isZero();
        assertThat(result.warnings()).anyMatch(w -> w.contains("知识库模块未启用"));
    }

    // ---------- helpers ----------

    private static RagExtraMetric extraMetric(String code, int minConfidence) {
        RagExtraMetric m = new RagExtraMetric();
        m.setCode(code);
        m.setMinConfidence(minConfidence);
        return m;
    }

    /** 构造一个检索到片段且模型返回 SBC 找到的答案。 */
    private static FilingAnswer answerWithSbc(String period, int confidence) {
        FilingAnswer answer = new FilingAnswer();
        answer.setChunks(List.of(FilingChunk.builder()
                .chunkId("chunk-1")
                .tags(Map.of(FilingTagKeys.DOCUMENT_ID, "doc-1"))
                .build()));
        answer.setChatResponse("{\"SBC\": {\"found\": true, \"value\": 1234.5, "
                + "\"unit\": \"million\", \"confidence\": " + confidence
                + ", \"evidence\": \"" + period + " 股份酬金 1234.5 百万\"}}");
        return answer;
    }
}
