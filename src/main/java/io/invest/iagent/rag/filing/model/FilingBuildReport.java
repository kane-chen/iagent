package io.invest.iagent.rag.filing.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个 ticker 建库结果汇总。
 */
@Data
public class FilingBuildReport {
    private String ticker;
    private int documents;
    private int chunks;
    private final List<String> errors = new ArrayList<>();

    public void incrementDocs() { documents++; }
    public void addChunks(int n) { chunks += n; }
    public void addError(String message) { errors.add(message); }
}
