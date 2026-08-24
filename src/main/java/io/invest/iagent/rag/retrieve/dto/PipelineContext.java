package io.invest.iagent.rag.retrieve.dto;

import io.invest.iagent.rag.retrieve.enums.QueryIntent;
import lombok.Data;

import java.util.ArrayList;

/**
 * Pipeline 工作台：组合 Request + State + Context
 */
@Data
public class PipelineContext {
    private PipelineRequest request;
    private PipelineState state;
    private PipelineRuntime runtime;

    public PipelineContext(PipelineRequest request, PipelineState state, PipelineRuntime runtime) {
        this.request = request;
        this.state = state != null ? state : new PipelineState();
        this.runtime = runtime;
    }

    public String getQuery() { return request.query; }

    /**
     * 判断是否需要执行检索阶段
     */
    public boolean ignoreRetrieval() {
        if (state.intent == QueryIntent.WEB_SEARCH) {
            return !request.webSearchEnabled;
        }
        return state.intent != null
                && state.intent != QueryIntent.KB_SEARCH
                && state.intent != QueryIntent.CLARIFICATION
                && state.intent != QueryIntent.SUMMARIZE;
    }

    /**
     * 深拷贝，用于并发分支隔离
     */
    public PipelineContext clone() {
        PipelineState newState = new PipelineState();
        newState.rewriteQuery = this.state.rewriteQuery;
        newState.intent = this.state.intent;
        newState.tagFilter = this.state.tagFilter; // 不可变对象，引用拷贝即可
        newState.history = new ArrayList<>(this.state.history);
        newState.entities = new ArrayList<>(this.state.entities);
        newState.graphResult = this.state.graphResult;
        newState.userContent = this.state.userContent;
        newState.imageDescription = this.state.imageDescription;
        newState.memoryPrompt = this.state.memoryPrompt;
        newState.usedMemories = new ArrayList<>(this.state.usedMemories);
        // 深拷贝 SearchResult 对象
        newState.searchResult = new ArrayList<>();
        for (SearchResult r : this.state.searchResult) {
            newState.searchResult.add(new SearchResult(r));
        }
        newState.rerankResult = new ArrayList<>();
        for (SearchResult r : this.state.rerankResult) {
            newState.rerankResult.add(new SearchResult(r));
        }
        newState.mergeResult = new ArrayList<>();
        for (SearchResult r : this.state.mergeResult) {
            newState.mergeResult.add(new SearchResult(r));
        }
        return new PipelineContext(this.request, newState, this.runtime);
    }
}
