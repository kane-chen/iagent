package io.invest.iagent.tools.web;

import io.invest.iagent.service.web.WebSearchProvider;
import io.invest.iagent.service.web.WebSearchRequest;
import io.invest.iagent.service.web.WebSearchService;
import io.invest.iagent.model.WebSearchResponseDTO;
import io.invest.iagent.model.WebSearchResultDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchToolTest {

    @Test
    void searchDelegatesToService() {
        WebSearchTool tool = new WebSearchTool(new WebSearchService(new FakeProvider(), 5, 10));

        WebSearchResponseDTO response = tool.search("2026 world cup schedule", 3, "US", "en", "year", null, null);

        assertTrue(response.isSuccess());
        assertEquals("2026 world cup schedule", response.getQuery());
        assertEquals("fake", response.getProvider());
        assertEquals(1, response.getResultCount());
        assertEquals("World Cup", response.getResults().get(0).getTitle());
    }

    @Test
    void toolAnnotationNameIsStable() throws Exception {
        Method method = WebSearchTool.class.getDeclaredMethod("search", String.class, Integer.class, String.class, String.class, String.class, String.class, String.class);
        io.agentscope.core.tool.Tool tool = method.getAnnotation(io.agentscope.core.tool.Tool.class);
        assertNotNull(tool);
        assertEquals("web_search", tool.name());
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
                    .resultCount(1)
                    .results(List.of(WebSearchResultDTO.builder()
                            .rank(1)
                            .title("World Cup")
                            .url("https://www.fifa.com/worldcup")
                            .snippet("Official schedule")
                            .source("fifa.com")
                            .publishedDate("2026")
                            .build()))
                    .build();
        }
    }
}
