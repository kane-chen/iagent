package io.invest.iagent.filingkb;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * filingkb 应用层配置（app.filing-kb.*）。
 * 仅当 app.rag.enabled=true 且 app.filing-kb.enabled=true 时生效。
 */
@Data
@ConfigurationProperties(prefix = "app.filing-kb")
public class FilingKbProperties {

    private boolean enabled = false;

    /** 全局财报知识库 id（所有 ticker 共用，ticker 作为标签过滤） */
    private String knowledgeBaseId = "filing";

    private Chunk chunk = new Chunk();
    private Search search = new Search();

    @Data
    public static class Chunk {
        /** 分块策略：财报使用 HEADING 以挂载段落标题标签 */
        private String strategy = "HEADING";
        private boolean parentChild = true;
        private int parentSize = 4096;
        private int childSize = 384;
        private int chunkOverlap = 80;
    }

    @Data
    public static class Search {
        /** 默认返回给 Agent 的片段数 */
        private int defaultTopK = 5;
    }
}
