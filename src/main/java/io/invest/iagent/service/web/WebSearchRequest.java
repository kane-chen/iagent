package io.invest.iagent.service.web;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class WebSearchRequest {
    private String query;
    private Integer maxResults;
    private String country;
    private String searchLanguage;
    private String freshness;
    private List<String> allowedDomains;
    private List<String> blockedDomains;
}
