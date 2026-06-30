package io.invest.iagent.service.web;

import io.invest.iagent.model.WebSearchResponseDTO;
import io.invest.iagent.model.WebSearchResultDTO;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class WebSearchService {

    private final WebSearchProvider provider;
    private final int defaultMaxResults;
    private final int maxResultsCap;

    public WebSearchService(WebSearchProvider provider, int defaultMaxResults, int maxResultsCap) {
        this.provider = provider;
        this.defaultMaxResults = Math.max(defaultMaxResults, 1);
        this.maxResultsCap = Math.max(maxResultsCap, 1);
    }

    public WebSearchResponseDTO search(String query, Integer maxResults, String country, String searchLanguage,
                                       String freshness, String allowedDomains, String blockedDomains) {
        if(StringUtils.isBlank(query)){
            return WebSearchResponseDTO.builder()
                    .success(false)
                    .query(query)
                    .provider(provider.name())
                    .resultCount(0)
                    .results(List.of())
                    .error("query不能为空")
                    .build();
        }
        int requested = Objects.nonNull(maxResults) ? maxResults : defaultMaxResults;
        int clamped = Math.min(Math.max(requested, 1), maxResultsCap);
        WebSearchRequest request = WebSearchRequest.builder()
                .query(query.trim())
                .maxResults(clamped)
                .country(StringUtils.trimToNull(country))
                .searchLanguage(StringUtils.trimToNull(searchLanguage))
                .freshness(StringUtils.trimToNull(freshness))
                .allowedDomains(parseDomains(allowedDomains))
                .blockedDomains(parseDomains(blockedDomains))
                .build();
        WebSearchResponseDTO response = provider.search(request);
        if(!response.isSuccess()){
            return response;
        }
        List<WebSearchResultDTO> filtered = filterDomains(response.getResults(), request.getAllowedDomains(), request.getBlockedDomains()).stream()
                .limit(clamped)
                .toList();
        return WebSearchResponseDTO.builder()
                .success(true)
                .query(request.getQuery())
                .provider(response.getProvider())
                .resultCount(filtered.size())
                .results(filtered)
                .metadata(Map.of(
                        "requested_max_results", requested,
                        "max_results", clamped,
                        "domain_filtered", !request.getAllowedDomains().isEmpty() || !request.getBlockedDomains().isEmpty()
                ))
                .build();
    }

    private List<String> parseDomains(String domains){
        if(StringUtils.isBlank(domains)){
            return List.of();
        }
        return Arrays.stream(domains.split(","))
                .map(StringUtils::trimToNull)
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .toList();
    }

    private List<WebSearchResultDTO> filterDomains(List<WebSearchResultDTO> results, List<String> allowedDomains, List<String> blockedDomains){
        if(results == null){
            return List.of();
        }
        return results.stream()
                .filter(result -> {
                    String host = host(result.getUrl());
                    if(!allowedDomains.isEmpty() && allowedDomains.stream().noneMatch(host::endsWith)){
                        return false;
                    }
                    return blockedDomains.stream().noneMatch(host::endsWith);
                })
                .toList();
    }

    private String host(String url){
        try{
            return StringUtils.defaultString(URI.create(url).getHost()).toLowerCase();
        }catch (Exception e){
            return "";
        }
    }
}
