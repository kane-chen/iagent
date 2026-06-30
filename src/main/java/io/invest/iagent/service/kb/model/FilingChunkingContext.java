package io.invest.iagent.service.kb.model;

import com.alibaba.fastjson2.JSONObject;
import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;

@Value
@Builder
public class FilingChunkingContext {
    String ticker;
    String documentId;
    Path sourceFile;
    JSONObject meta;
    String chunkType;
}
