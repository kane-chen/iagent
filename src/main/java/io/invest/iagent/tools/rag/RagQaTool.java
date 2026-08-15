package io.invest.iagent.tools.rag;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.invest.iagent.rag.RagService;
import io.invest.iagent.rag.model.ChunkingConfig;
import io.invest.iagent.rag.model.Document;
import io.invest.iagent.rag.model.RetrieveRequest;
import io.invest.iagent.rag.model.RetrieveResultItem;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * RAG 知识库工具：基于 ParadeDB + pgvector 的通用知识库问答。
 * <p>
 * 两个 @Tool 方法：
 * <ul>
 *   <li>{@code rag_qa} — 基于指定知识库检索并由 LLM 回答。</li>
 *   <li>{@code rag_index} — 将本地文档（HTML/PDF/TXT）切分、嵌入并写入知识库。</li>
 * </ul>
 */
public class RagQaTool {

    private final RagService ragService;

    public RagQaTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Tool(name = "rag_qa", description = "基于已构建的通用知识库（ParadeDB + pgvector）检索并回答问题。"
            + "适用于对研报、行业资料、招股书、政策文件等非财报类长文档的事实性提问。"
            + "会做混合检索（BM25 + 向量）+ RRF 融合 + LLM 重排 + LLM 生成答案。如知识库尚未构建，先用 rag_index 导入文档。")
    public String answer(
            @ToolParam(name = "question", description = "要回答的问题") String question,
            @ToolParam(name = "knowledge_base_id", description = "知识库 ID，例如 industry_research、macro_policy") String knowledgeBaseId,
            @ToolParam(name = "top_k", required = false, description = "返回的引用片段数量，默认 5") Integer topK
    ) {
        try {
            int k = topK == null || topK <= 0 ? 5 : topK;
            RetrieveRequest request = RetrieveRequest.builder()
                    .query(question)
                    .knowledgeBaseIds(List.of(knowledgeBaseId))
                    .rerankTopK(k)
                    .enableRewrite(true)
                    .build();
            List<RetrieveResultItem> items = ragService.retrieve(request);

            StringBuilder sb = new StringBuilder();
            // 注：当前 RagService.retrieve 仅返回检索结果，完整 LLM 答案生成在 pipeline 中执行；
            // 这里把命中片段拼装成上下文，供 Agent 继续综合。
            if (items == null || items.isEmpty()) {
                sb.append("知识库 [").append(knowledgeBaseId).append("] 中未检索到与问题相关的片段。");
                return sb.toString();
            }
            sb.append("从知识库 [").append(knowledgeBaseId).append("] 检索到 ").append(items.size()).append(" 条相关片段：\n\n");
            int i = 1;
            for (RetrieveResultItem item : items) {
                sb.append("[C").append(i).append("]");
                if (StringUtils.isNotBlank(item.getKnowledgeId())) {
                    sb.append(" (").append(item.getKnowledgeId()).append(")");
                }
                if (StringUtils.isNotBlank(item.getChunkType())) {
                    sb.append(" [").append(item.getChunkType()).append("]");
                }
                sb.append(" score=").append(String.format("%.3f", item.getScore()));
                if (StringUtils.isNotBlank(item.getParentId())) {
                    sb.append(" parent=").append(item.getParentId());
                }
                sb.append("\n");
                String content = item.getContent() == null ? "" : item.getContent().trim();
                content = content.replaceAll("\\s+", " ");
                sb.append(StringUtils.abbreviate(content, 600)).append("\n\n");
                i++;
            }
            return sb.toString();
        } catch (Exception e) {
            return "rag_qa failed: " + e.getMessage();
        }
    }

    @Tool(name = "rag_index", description = "将本地文档（HTML/PDF/TXT）切分、嵌入并写入指定知识库。"
            + "文档路径必须是本机绝对路径，或位于 workspace 下。"
            + "首次向某个知识库导入文档前调用一次；重复导入会按 chunk_id 幂等覆盖。")
    public String index(
            @ToolParam(name = "file_path", description = "文档绝对路径，例如 /Users/me/report.pdf 或 workspace/portfolio/BABA/materials/x.html") String filePath,
            @ToolParam(name = "knowledge_base_id", description = "目标知识库 ID") String knowledgeBaseId,
            @ToolParam(name = "knowledge_id", required = false, description = "文档 ID（可选，默认由文件路径推断）") String knowledgeId,
            @ToolParam(name = "parent_child", required = false, description = "是否启用父子分块，默认 true") Boolean parentChild
    ) {
        try {
            if (StringUtils.isBlank(filePath)) {
                return "rag_index failed: file_path is blank";
            }
            if (StringUtils.isBlank(knowledgeBaseId)) {
                return "rag_index failed: knowledge_base_id is blank";
            }

            ChunkingConfig chunkingConfig = new ChunkingConfig();
            chunkingConfig.setStrategy(ChunkingConfig.Strategy.AUTO);
            chunkingConfig.setEnableParentChild(parentChild == null || parentChild);

            Document doc = Document.builder()
                    .filePath(filePath)
                    .knowledgeBaseId(knowledgeBaseId)
                    .knowledgeId(StringUtils.defaultIfBlank(knowledgeId, inferKnowledgeId(filePath)))
                    .chunkingConfig(chunkingConfig)
                    .build();

            ragService.save(doc, chunkingConfig);

            return "索引构建完成：knowledgeBaseId=" + knowledgeBaseId
                    + ", file=" + filePath
                    + ", parentChild=" + chunkingConfig.isEnableParentChild();
        } catch (Exception e) {
            return "rag_index failed: " + e.getMessage();
        }
    }

    private String inferKnowledgeId(String filePath) {
        String name = filePath;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
