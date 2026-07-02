package io.invest.iagent.service.web.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.invest.iagent.model.WebSearchResponseDTO;
import io.invest.iagent.model.WebSearchResultDTO;
import io.invest.iagent.service.web.WebSearchProvider;
import io.invest.iagent.service.web.WebSearchRequest;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TavilyWebSearchProvider implements WebSearchProvider {

    private static final String TAVILY_SEARCH_URL = "https://api.tavily.com/search";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String searchDepth;
    private final String topic;
    private final boolean includeAnswer;
    private final boolean includeRawContent;

    public TavilyWebSearchProvider(String apiKey, int timeoutSeconds, String searchDepth, String topic,
                                   boolean includeAnswer, boolean includeRawContent) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
        this.objectMapper = new ObjectMapper();
        this.apiKey = apiKey;
        this.searchDepth = StringUtils.defaultIfBlank(searchDepth, "basic");
        this.topic = StringUtils.defaultIfBlank(topic, "general");
        this.includeAnswer = includeAnswer;
        this.includeRawContent = includeRawContent;
    }

    @Override
    public String name() {
        return "tavily";
    }

    @Override
    public WebSearchResponseDTO search(WebSearchRequest request) {
        if (StringUtils.isBlank(apiKey)) {
            return failure(request, "TAVILY_API_KEY is required", null);
        }
        try {
            String body = buildBody(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(TAVILY_SEARCH_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return failure(request, "Tavily search request failed: HTTP " + response.statusCode(), Map.of("status", response.statusCode()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode resultsNode = root.path("results");
            List<WebSearchResultDTO> results = new ArrayList<>();
            for (int i = 0; i < resultsNode.size(); i++) {
                JsonNode item = resultsNode.get(i);
                results.add(WebSearchResultDTO.builder()
                        .rank(i + 1)
                        .title(item.path("title").asText())
                        .url(item.path("url").asText())
                        .snippet(item.path("content").asText())
                        .source(sourceFromUrl(item.path("url").asText()))
                        .publishedDate(item.path("published_date").asText(null))
                        .build());
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("provider", name());
            if (root.hasNonNull("answer")) {
                metadata.put("answer", root.get("answer").asText());
            }

            return WebSearchResponseDTO.builder()
                    .success(true)
                    .query(request.getQuery())
                    .provider(name())
                    .resultCount(results.size())
                    .results(results)
                    .metadata(metadata)
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(request, "Tavily search interrupted", null);
        } catch (Exception e) {
            return failure(request, e.getMessage(), Map.of("exception", e.getClass().getSimpleName()));
        }
    }

    private String buildBody(WebSearchRequest request) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("api_key", apiKey);
        body.put("query", request.getQuery());
        body.put("max_results", request.getMaxResults());
        body.put("search_depth", searchDepth);
        body.put("topic", topic);
        body.put("include_answer", includeAnswer);
        body.put("include_raw_content", includeRawContent);
        body.put("include_images", false);

        String timeRange = tavilyTimeRange(request.getFreshness());
        if (StringUtils.isNotBlank(timeRange)) {
            body.put("time_range", timeRange);
        }
        if (StringUtils.isNotBlank(request.getCountry())) {
            body.put("country", request.getCountry());
        }
        if (request.getAllowedDomains() != null && !request.getAllowedDomains().isEmpty()) {
            body.putArray("include_domains").addAll(request.getAllowedDomains().stream()
                    .map(objectMapper.getNodeFactory()::textNode)
                    .toList());
        }
        if (request.getBlockedDomains() != null && !request.getBlockedDomains().isEmpty()) {
            body.putArray("exclude_domains").addAll(request.getBlockedDomains().stream()
                    .map(objectMapper.getNodeFactory()::textNode)
                    .toList());
        }
        return objectMapper.writeValueAsString(body);
    }

    private String tavilyTimeRange(String freshness) {
        if (StringUtils.isBlank(freshness)) return null;
        return switch (freshness.trim().toLowerCase()) {
            case "pd", "day", "d" -> "day";
            case "pw", "week", "w" -> "week";
            case "pm", "month", "m" -> "month";
            case "py", "year", "y" -> "year";
            default -> freshness;
        };
    }

    private String sourceFromUrl(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private WebSearchResponseDTO failure(WebSearchRequest request, String error, Map<String, Object> metadata) {
        return WebSearchResponseDTO.builder()
                .success(false)
                .query(request != null ? request.getQuery() : null)
                .provider(name())
                .resultCount(0)
                .results(List.of())
                .error(error)
                .metadata(metadata)
                .build();
    }
}
