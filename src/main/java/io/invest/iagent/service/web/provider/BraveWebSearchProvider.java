package io.invest.iagent.service.web.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.invest.iagent.model.WebSearchResponseDTO;
import io.invest.iagent.model.WebSearchResultDTO;
import io.invest.iagent.service.web.WebSearchProvider;
import io.invest.iagent.service.web.WebSearchRequest;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BraveWebSearchProvider implements WebSearchProvider {

    private static final String BRAVE_SEARCH_URL = "https://api.search.brave.com/res/v1/web/search";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String defaultCountry;
    private final String defaultLanguage;
    private final String safeSearch;

    public BraveWebSearchProvider(String apiKey, int timeoutSeconds, String defaultCountry, String defaultLanguage, String safeSearch) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
        this.objectMapper = new ObjectMapper();
        this.apiKey = apiKey;
        this.defaultCountry = defaultCountry;
        this.defaultLanguage = defaultLanguage;
        this.safeSearch = StringUtils.defaultIfBlank(safeSearch, "moderate");
    }

    @Override
    public String name() {
        return "brave";
    }

    @Override
    public WebSearchResponseDTO search(WebSearchRequest request) {
        if(StringUtils.isBlank(apiKey)){
            return failure(request, "BRAVE_SEARCH_API_KEY is required", null);
        }
        try{
            String url = buildUrl(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", apiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if(response.statusCode() < 200 || response.statusCode() >= 300){
                return failure(request, "Brave search request failed: HTTP " + response.statusCode(), Map.of("status", response.statusCode()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode resultsNode = root.path("web").path("results");
            List<WebSearchResultDTO> results = new ArrayList<>();
            for(int i=0;i<resultsNode.size();i++){
                JsonNode item = resultsNode.get(i);
                results.add(WebSearchResultDTO.builder()
                        .rank(i + 1)
                        .title(item.path("title").asText())
                        .url(item.path("url").asText())
                        .snippet(item.path("description").asText())
                        .source(sourceFromUrl(item.path("url").asText()))
                        .publishedDate(item.path("age").asText())
                        .build());
            }
            return WebSearchResponseDTO.builder()
                    .success(true)
                    .query(request.getQuery())
                    .provider(name())
                    .resultCount(results.size())
                    .results(results)
                    .metadata(Map.of("provider", name()))
                    .build();
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
            return failure(request, "Brave search interrupted", null);
        }catch (Exception e){
            return failure(request, e.getMessage(), Map.of("exception", e.getClass().getSimpleName()));
        }
    }

    private String buildUrl(WebSearchRequest request){
        Map<String,String> params = new LinkedHashMap<>();
        params.put("q", request.getQuery());
        params.put("count", String.valueOf(request.getMaxResults()));
        params.put("safesearch", safeSearch);
        String country = StringUtils.firstNonBlank(request.getCountry(), defaultCountry);
        if(StringUtils.isNotBlank(country)){
            params.put("country", country);
        }
        String language = StringUtils.firstNonBlank(request.getSearchLanguage(), defaultLanguage);
        if(StringUtils.isNotBlank(language)){
            params.put("search_lang", language);
        }
        if(StringUtils.isNotBlank(request.getFreshness())){
            params.put("freshness", request.getFreshness());
        }
        String query = params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .reduce((a,b)->a + "&" + b)
                .orElse("");
        return BRAVE_SEARCH_URL + "?" + query;
    }

    private String encode(String value){
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String sourceFromUrl(String url){
        try{
            return URI.create(url).getHost();
        }catch (Exception e){
            return null;
        }
    }

    private WebSearchResponseDTO failure(WebSearchRequest request, String error, Map<String,Object> metadata){
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
