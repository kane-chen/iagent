package io.invest.iagent.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WebSearchResultDTO {
    private Integer rank;
    private String title;
    private String url;
    private String snippet;
    private String source;
    private String publishedDate;
}
