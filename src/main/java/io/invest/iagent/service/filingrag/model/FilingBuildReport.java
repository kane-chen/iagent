package io.invest.iagent.service.filingrag.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class FilingBuildReport {
    private String ticker;
    private String backend;
    @Builder.Default
    private int documentsProcessed = 0;
    @Builder.Default
    private int chunksIndexed = 0;
    @Builder.Default
    private long elapsedMs = 0;
    @Builder.Default
    private List<String> errors = new ArrayList<>();
}
