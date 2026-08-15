package io.invest.iagent.rag.retrieve.dto;

import io.invest.iagent.rag.retrieve.enums.QueryIntent;
import io.invest.iagent.rag.retrieve.model.SearchResult;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Pipeline中间状态（可变）
 * 对应 Go 中的 PipelineState
 */
@Data
public class PipelineState {
    // Query理解阶段产出
    public String rewriteQuery;
    public QueryIntent intent;
    public List<String> history = new ArrayList<>(); // 历史对话
    
    // 检索阶段产出
    public List<SearchResult> searchResult = new ArrayList<>(); // 粗排结果
    public List<String> entities = new ArrayList<>(); // 识别出的实体
    
    // 图谱/知识图谱阶段产出
    public String graphResult; 
    
    // 重排阶段产出
    public List<SearchResult> rerankResult = new ArrayList<>();
    
    // 合并与过滤阶段产出
    public List<SearchResult> mergeResult = new ArrayList<>();
    
    // 上下文构建阶段产出
    public String renderedContexts;
    
    // 用户输入内容（可能包含图片描述等）
    public String userContent;
    public String imageDescription;
    
    // LLM 生成阶段产出
    public String chatResponse;
    
    // 记忆相关（可选）
    public String memoryPrompt;
    public List<String> usedMemories = new ArrayList<>();

    // 清理或重置状态的方法（可选）
    public void clear() {
        this.searchResult.clear();
        this.rerankResult.clear();
        this.mergeResult.clear();
        // ... 根据业务需要决定哪些字段需要清空
    }
}
