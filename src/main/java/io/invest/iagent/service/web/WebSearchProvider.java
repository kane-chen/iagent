package io.invest.iagent.service.web;

import io.invest.iagent.model.WebSearchResponseDTO;

public interface WebSearchProvider {
    String name();
    WebSearchResponseDTO search(WebSearchRequest request);
}
