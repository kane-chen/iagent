package io.invest.iagent.rag.chatting;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.invest.iagent.rag.config.RagProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

@Slf4j
@Service
public class OllamaChatter implements Chatter {

    @Autowired
    private RagProperties ragProperties;

    private ReActAgent agent ;

    @PostConstruct
    public void init() {
        Model model = OpenAIChatModel.builder()
                .baseUrl(ragProperties.getLlm().getBaseUrl())
                .apiKey(ragProperties.getLlm().getApiKey())
                .modelName(ragProperties.getLlm().getModel())
                .stream(false)
                .build();
        agent = ReActAgent.builder()
                .model(model)
                .build();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        return doChat(userPrompt,systemPrompt);
    }

    /**
     * 调用LLM chat接口（全参数控制）。
     *
     * @param userPrompt 请求参数
     * @param systemPrompt 系统提示词
     * @return 模型输出文本；当content字段为空时自动回退到reasoning_content/reasoning/thinking字段原文（失败时返回空字符串）
     */
    protected String doChat(String userPrompt,String systemPrompt) {
        try {
            String input = systemPrompt + "\n 用户请求: \n"+userPrompt ;
            Mono<Msg> result = agent.call(input) ;
            Msg message = result.block(Duration.ofSeconds(ragProperties.getLlm().getTimeoutSeconds())) ;
            return Objects.requireNonNull(message).getTextContent() ;
        } catch (Exception e) {
            log.warn("LLM chat failed: {}", e.getMessage());
            return "";
        }
    }

}
