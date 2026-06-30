package io.invest.iagent.service.web;

import io.invest.iagent.model.WebSearchResponseDTO;
import io.invest.iagent.model.WebSearchResultDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchServiceTest {

    @Test
    void blankQueryReturnsFailure() {
        WebSearchService service = new WebSearchService(new FakeProvider(), 5, 10);

        WebSearchResponseDTO response = service.search(" ", 5, null, null, null, null, null);

        assertFalse(response.isSuccess());
        assertEquals("query不能为空", response.getError());
    }

    @Test
    void clampsMaxResults() {
        WebSearchService service = new WebSearchService(new FakeProvider(), 5, 2);

        WebSearchResponseDTO response = service.search("world cup", 99, null, null, null, null, null);

        assertTrue(response.isSuccess());
        assertEquals(2, response.getResultCount());
        assertEquals(2, response.getResults().size());
        assertEquals(99, response.getMetadata().get("requested_max_results"));
        assertEquals(2, response.getMetadata().get("max_results"));
    }

    @Test
    void filtersAllowedDomains() {
        WebSearchService service = new WebSearchService(new FakeProvider(), 5, 10);

        WebSearchResponseDTO response = service.search("filing", 5, null, null, null, "sec.gov", null);

        assertTrue(response.isSuccess());
        assertEquals(1, response.getResultCount());
        assertEquals("https://www.sec.gov/news", response.getResults().get(0).getUrl());
    }

    @Test
    void filtersBlockedDomains() {
        WebSearchService service = new WebSearchService(new FakeProvider(), 5, 10);

        WebSearchResponseDTO response = service.search("filing", 5, null, null, null, null, "example.com");

        assertTrue(response.isSuccess());
        assertEquals(2, response.getResultCount());
        assertTrue(response.getResults().stream().noneMatch(v -> v.getUrl().contains("example.com")));
    }

    @Test
    void providerFailurePassesThrough() {
        WebSearchService service = new WebSearchService(new FailingProvider(), 5, 10);

        WebSearchResponseDTO response = service.search("news", 5, null, null, null, null, null);

        assertFalse(response.isSuccess());
        assertEquals("provider failed", response.getError());
    }

    private static class FakeProvider implements WebSearchProvider {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebSearchResponseDTO search(WebSearchRequest request) {
            return WebSearchResponseDTO.builder()
                    .success(true)
                    .query(request.getQuery())
                    .provider(name())
                    .resultCount(3)
                    .results(List.of(
                            WebSearchResultDTO.builder().rank(1).title("SEC News").url("https://www.sec.gov/news").snippet("SEC update").source("sec.gov").build(),
                            WebSearchResultDTO.builder().rank(2).title("Example").url("https://example.com/a").snippet("Example result").source("example.com").build(),
                            WebSearchResultDTO.builder().rank(3).title("Anthropic").url("https://www.anthropic.com/news").snippet("AI news").source("anthropic.com").build()
                    ))
                    .build();
        }
    }

    private static class FailingProvider implements WebSearchProvider {
        @Override
        public String name() {
            return "fail";
        }

        @Override
        public WebSearchResponseDTO search(WebSearchRequest request) {
            return WebSearchResponseDTO.builder()
                    .success(false)
                    .query(request.getQuery())
                    .provider(name())
                    .resultCount(0)
                    .results(List.of())
                    .error("provider failed")
                    .build();
        }
    }
}
