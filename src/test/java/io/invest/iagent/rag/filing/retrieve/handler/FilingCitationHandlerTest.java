package io.invest.iagent.rag.filing.retrieve.handler;

import io.invest.iagent.rag.filing.retrieve.FilingTagKeys;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.PipelineRequest;
import io.invest.iagent.rag.retrieve.dto.PipelineState;
import io.invest.iagent.rag.retrieve.dto.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FilingCitationHandlerTest {

    private ChatManage newChatManage(String domain, List<SearchResult> results) {
        PipelineRequest req = new PipelineRequest();
        req.domain = domain;
        PipelineState state = new PipelineState();
        state.setMergeResult(results);
        return new ChatManage(req, state,
                new PipelineContext(null, null, "trace"));
    }

    @Test
    void formatsCitationFromTags() {
        SearchResult r = new SearchResult();
        r.setTags(new HashMap<>(Map.of(
                FilingTagKeys.TICKER, "00700",
                FilingTagKeys.FISCAL_YEAR, "2026",
                FilingTagKeys.FISCAL_PERIOD, "2026Q1",
                FilingTagKeys.HEADING, "管理层讨论与分析 > 收入")));

        ChatManage cm = newChatManage(FilingTagKeys.DOMAIN, List.of(r));
        new FilingCitationHandler().handle(null,  cm);

        assertThat(r.getMetadata().get("citation"))
                .isEqualTo("[C1] 00700 2026 2026Q1 管理层讨论与分析 > 收入");
        assertThat(r.getMetadata()).containsEntry("ticker", "00700");
    }

    @Test
    void fallsBackToContextHeaderWhenHeadingMissing() {
        SearchResult r = new SearchResult();
        r.setContextHeader("财务报表附注");
        r.setTags(new HashMap<>(Map.of(FilingTagKeys.TICKER, "AAPL")));

        ChatManage cm = newChatManage(FilingTagKeys.DOMAIN, List.of(r));
        new FilingCitationHandler().handle(null,  cm);

        assertThat(r.getMetadata().get("citation")).isEqualTo("[C1] AAPL 财务报表附注");
    }

    @Test
    void nonFilingDomainIsIgnored() {
        SearchResult r = new SearchResult();
        r.setTags(new HashMap<>(Map.of(FilingTagKeys.TICKER, "00700")));

        ChatManage cm = newChatManage("other", List.of(r));
        new FilingCitationHandler().handle(null,  cm);

        assertThat(r.getMetadata()).doesNotContainKey("citation");
    }
}
