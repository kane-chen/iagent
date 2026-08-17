package io.invest.iagent.filingkb.retrieve.handler;

import io.invest.iagent.filingkb.retrieve.FilingTagKeys;
import io.invest.iagent.rag.model.TagCondition;
import io.invest.iagent.rag.model.TagFilter;
import io.invest.iagent.rag.repository.ChunkRepository;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.PipelineRequest;
import io.invest.iagent.rag.retrieve.dto.PipelineState;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.event.EventBus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FilingPeriodNormalizeHandlerTest {

    private static final String KB = "filing";

    /** 模拟库里该 ticker 实际存在的周期（无序，仓储层会排序） */
    private ChunkRepository repoWith(List<String> periods) {
        ChunkRepository repo = mock(ChunkRepository.class);
        when(repo.findTagValues(eq(KB), eq(FilingTagKeys.FISCAL_PERIOD), any())).thenReturn(periods);
        return repo;
    }

    private ChatManage newChatManage(String query, TagFilter prefill) {
        PipelineRequest req = new PipelineRequest();
        req.domain = FilingTagKeys.DOMAIN;
        req.tagFilter = prefill;
        PipelineState state = new PipelineState();
        state.rewriteQuery = query;
        return new ChatManage(req, state,
                new PipelineContext(new EventBus(), null, null, "t"));
    }

    @Test
    void latestResolvesToMaxPeriod() {
        ChunkRepository repo = repoWith(List.of("2024Q4", "2025Q1", "2025Q2", "FY2024"));
        ChatManage cm = newChatManage("腾讯最新财报的收入",
                TagFilter.of(TagCondition.eq(FilingTagKeys.TICKER, "00700")));

        new FilingPeriodNormalizeHandler(repo, KB)
                .onEvent(null, EventType.QUERY_UNDERSTAND, cm);

        TagCondition c = FilingHandlerSupport.findCondition(cm.getState().tagFilter,
                FilingTagKeys.FISCAL_PERIOD).orElseThrow();
        assertThat(c.getValues()).containsExactly("2025Q2");
    }

    @Test
    void lastNEnumeratesLatestNPeriods() {
        ChunkRepository repo = repoWith(List.of("2025Q1", "2025Q2", "2025Q3", "2025Q4"));
        ChatManage cm = newChatManage("近3个季度的毛利率变化", null);

        new FilingPeriodNormalizeHandler(repo, KB)
                .onEvent(null, EventType.QUERY_UNDERSTAND, cm);

        TagCondition c = FilingHandlerSupport.findCondition(cm.getState().tagFilter,
                FilingTagKeys.FISCAL_PERIOD).orElseThrow();
        assertThat(c.getValues()).containsExactly("2025Q2", "2025Q3", "2025Q4");
    }

    @Test
    void yearAgoResolvesToSameOrdinalLastYear() {
        ChunkRepository repo = repoWith(List.of("2025Q1", "2025Q2", "2025Q3", "2026Q1"));
        ChatManage cm = newChatManage("去年同期的净利润", null);

        new FilingPeriodNormalizeHandler(repo, KB)
                .onEvent(null, EventType.QUERY_UNDERSTAND, cm);

        TagCondition c = FilingHandlerSupport.findCondition(cm.getState().tagFilter,
                FilingTagKeys.FISCAL_PERIOD).orElseThrow();
        // 最新 2026Q1，去年同期 2025Q1
        assertThat(c.getValues()).containsExactly("2025Q1");
    }

    @Test
    void explicitPeriodIsNotOverridden() {
        ChunkRepository repo = repoWith(List.of("2025Q1", "2025Q2", "2025Q3", "2025Q4"));
        TagFilter prefill = TagFilter.of(TagCondition.eq(FilingTagKeys.FISCAL_PERIOD, "2025Q1"));
        ChatManage cm = newChatManage("2025Q1 收入", prefill);

        new FilingPeriodNormalizeHandler(repo, KB)
                .onEvent(null, EventType.QUERY_UNDERSTAND, cm);

        // 请求已带显式周期：handler 提前返回，不在 state 中做相对时间归一化
        // （预填 filter 由 SearchParallelHandler 直接使用）
        assertThat(cm.getState().tagFilter).isNull();
    }

    @Test
    void noRelativeIntentLeavesFilterUntouched() {
        ChunkRepository repo = repoWith(List.of("2025Q1"));
        ChatManage cm = newChatManage("收入情况", null);

        new FilingPeriodNormalizeHandler(repo, KB)
                .onEvent(null, EventType.QUERY_UNDERSTAND, cm);

        assertThat(cm.getState().tagFilter).isNull();
    }

    @Test
    void nonFilingDomainIsIgnored() {
        ChunkRepository repo = repoWith(List.of("2025Q1"));
        PipelineRequest req = new PipelineRequest();
        req.domain = "other";
        PipelineState state = new PipelineState();
        state.rewriteQuery = "最新财报";
        ChatManage cm = new ChatManage(req, state,
                new PipelineContext(new EventBus(), null, null, "t"));

        new FilingPeriodNormalizeHandler(repo, KB)
                .onEvent(null, EventType.QUERY_UNDERSTAND, cm);

        assertThat(cm.getState().tagFilter).isNull();
    }
}
