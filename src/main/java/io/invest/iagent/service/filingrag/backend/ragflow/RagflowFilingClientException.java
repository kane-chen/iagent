package io.invest.iagent.service.filingrag.backend.ragflow;

import lombok.Getter;

/**
 * Runtime exception for RAGFlow filing client errors (HTTP status != 2xx or business code != 0).
 */
@Getter
public class RagflowFilingClientException extends RuntimeException {

    private final int code;

    public RagflowFilingClientException(int code, String message) {
        super(message);
        this.code = code;
    }

    public RagflowFilingClientException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

}
