package io.invest.iagent.rag.filing.model;

import lombok.Data;

import java.util.List;

@Data
public class FilingAnswer {

    // LLM 生成阶段产出
    private String chatResponse;
    // 相关的知识库片段
    private List<FilingChunk> chunks ;

}
