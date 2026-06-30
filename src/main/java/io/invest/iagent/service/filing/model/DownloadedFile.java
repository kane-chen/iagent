package io.invest.iagent.service.filing.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DownloadedFile {
    private String name;
    private String uri;
    private String sha256;
    private long size;
    private String sourceUrl;
    private String contentType;
    private String ingestedAt;
}
