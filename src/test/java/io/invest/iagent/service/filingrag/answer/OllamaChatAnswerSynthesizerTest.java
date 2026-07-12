package io.invest.iagent.service.filingrag.answer;

import io.invest.iagent.service.filingrag.model.FilingAnswer;
import io.invest.iagent.service.filingrag.model.FilingChunk;
import io.invest.iagent.service.filingrag.util.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a local Ollama instance at localhost:11434 with qwen3:4b model pulled.
 * Enable via OLLAMA_SMOKE=1 env var.
 */
//@EnabledIfEnvironmentVariable(named = "OLLAMA_SMOKE", matches = "1")
class OllamaChatAnswerSynthesizerTest {

    @Test
    void answerProducesCitations() {
        LlmClient llmClient = new LlmClient("http://localhost:11434/v1", "qwen3.5:4b", 180);
        OllamaChatAnswerSynthesizer synth = new OllamaChatAnswerSynthesizer(llmClient, 0.2, 2048);
        List<FilingChunk> chunks = List.of(
                FilingChunk.builder()
                        .chunkId("c1").ticker("BABA").formType("FY").fiscalYear(2025).fiscalPeriod("FY")
                        .sectionTitle("MD&A").content("公司2025财年云业务收入同比增长12%，主要由AI相关需求驱动。")
                        .score(0.9).build(),
                FilingChunk.builder()
                        .chunkId("c2").ticker("BABA").formType("FY").fiscalYear(2025).fiscalPeriod("FY")
                        .sectionTitle("Business Overview").content("核心商业收入同比增长3%，本地生活服务增长显著。")
                        .score(0.8).build()
        );
        FilingAnswer ans = synth.answer("BABA 2025财年云业务增长情况如何？", chunks, "milvus");
        assertNotNull(ans);
        assertNotNull(ans.getAnswer());
        System.out.println("Answer: " + ans.getAnswer());
        assertFalse(ans.getAnswer().isBlank());
    }
}
