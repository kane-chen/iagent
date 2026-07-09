package io.invest.iagent.service.filingrag.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class FilingQueryResult {
    private String queryId;
    private String question;
    private String ticker;
    private String backend;
    @Builder.Default
    private long elapsedMs = 0;
    @Builder.Default
    private List<FilingChunk> chunks = new ArrayList<>();
}
