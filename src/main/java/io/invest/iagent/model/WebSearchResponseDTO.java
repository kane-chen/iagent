package io.invest.iagent.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class WebSearchResponseDTO {
    private boolean success;
    private String query;
    private String provider;
    private Integer resultCount;
    private List<WebSearchResultDTO> results;
    private String error;
    private Map<String, Object> metadata;
}
