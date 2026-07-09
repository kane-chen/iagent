package io.invest.iagent.service.filingrag.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class FilingAnswer {
    private String queryId;
    private String question;
    private String answer;
    private String backend;
    private String model;
    @Builder.Default
    private long elapsedMs = 0;
    @Builder.Default
    private List<FilingChunk> citations = new ArrayList<>();
}
