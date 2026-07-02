package io.invest.iagent.service.kb.backend.ragflow;

/**
 * RAGFlow API 调用异常。code 为 HTTP 状态码或 RAGFlow 业务 code，负值表示客户端本地错误。
 */
public class RagflowClientException extends RuntimeException {

    private final int code;

    public RagflowClientException(int code, String message) {
        super(message);
        this.code = code;
    }

    public RagflowClientException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
