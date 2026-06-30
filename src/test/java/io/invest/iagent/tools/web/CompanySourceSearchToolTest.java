package io.invest.iagent.tools.web;

import io.invest.iagent.model.WebSearchResponseDTO;
import io.invest.iagent.model.WebSearchResultDTO;
import io.invest.iagent.service.web.WebSearchProvider;
import io.invest.iagent.service.web.WebSearchRequest;
import io.invest.iagent.service.web.WebSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompanySourceSearchToolTest {

    @Test
    void mediaSearchAllowsConfiguredDomainsOnly() {
        CompanySourceSearchTool tool = new CompanySourceSearchTool(new WebSearchService(new FakeProvider(), 5, 10), "reuters.com", "");

        WebSearchResponseDTO response = tool.searchMediaReports("company growth", 5, "US", "en", null);

        assertTrue(response.isSuccess());
        assertEquals(1, response.getResultCount());
        assertTrue(response.getResults().get(0).getUrl().contains("reuters.com"));
        assertEquals("external media report", response.getMetadata().get("source_class"));
    }

    @Test
    void brokerSearchFailsClosedWithoutConfiguredDomains() {
        CompanySourceSearchTool tool = new CompanySourceSearchTool(new WebSearchService(new FakeProvider(), 5, 10), "reuters.com", "");

        WebSearchResponseDTO response = tool.searchBrokerResearch("company broker report", 5, "US", "en", null);

        assertTrue(response.isSuccess());
        assertEquals(0, response.getResultCount());
        assertEquals(true, response.getMetadata().get("fail_closed"));
    }

    @Test
    void brokerSearchUsesConfiguredDomains() {
        CompanySourceSearchTool tool = new CompanySourceSearchTool(new WebSearchService(new FakeProvider(), 5, 10), "reuters.com", "goldmansachs.com");

        WebSearchResponseDTO response = tool.searchBrokerResearch("company broker report", 5, "US", "en", null);

        assertTrue(response.isSuccess());
        assertEquals(1, response.getResultCount());
        assertTrue(response.getResults().get(0).getUrl().contains("goldmansachs.com"));
        assertEquals("broker research", response.getMetadata().get("source_class"));
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
                    .provider("fake")
                    .query(request.getQuery())
                    .resultCount(3)
                    .results(List.of(
                            result("Reuters report", "https://www.reuters.com/markets/company"),
                            result("Blog rumor", "https://example-blog.test/post"),
                            result("Goldman Sachs research", "https://www.goldmansachs.com/insights/report")
                    ))
                    .build();
        }

        private WebSearchResultDTO result(String title, String url) {
            return WebSearchResultDTO.builder()
                    .title(title)
                    .url(url)
                    .snippet(title)
                    .source(title)
                    .publishedDate("2026-01-01")
                    .build();
        }
    }
}
