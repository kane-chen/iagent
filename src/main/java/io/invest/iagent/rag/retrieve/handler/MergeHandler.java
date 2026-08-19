package io.invest.iagent.rag.retrieve.handler;

import io.invest.iagent.rag.repository.ChunkRepository;
import io.invest.iagent.rag.repository.ChunkRetrieveResult;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * CHUNK_MERGE：parent 回填、去重、相邻合并
 */
@Slf4j
@Service
public class MergeHandler implements Handler {

    @Autowired
    private ChunkRepository chunkRepository;

    @Override
    public String name() {
        return "CHUNK_MERGE";
    }

    @Override
    public void handle(PipelineContext ctx, ChatManage cm) {
        if (!cm.needsRetrieval()){
            return ;
        }

        // 修复：优先使用 rerank-Result，否则使用 search Result
        List<SearchResult> input = !cm.getState().getRerankResult().isEmpty()
                ? cm.getState().getRerankResult()
                : cm.getState().getSearchResult();

        // 去重（按 id）
        Map<String, SearchResult> dedup = new LinkedHashMap<>();
        for (SearchResult r : input) {
            dedup.putIfAbsent(r.id, r);
        }
        List<SearchResult> merged = new ArrayList<>(dedup.values());

        // Parent 回填
        backfillParents(merged);

        // 按分数降序
        merged.sort((a, b) -> Double.compare(b.score, a.score));

        cm.getState().setMergeResult(merged);
        log.debug("Merge completed: {} results", merged.size());
    }

    private void backfillParents(List<SearchResult> results) {
        List<String> parentIds = results.stream()
                .map(r -> r.parentId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();

        if (parentIds.isEmpty()) return;

        try {
            List<ChunkRetrieveResult> parents = chunkRepository.findByChunkIds(parentIds);
            Map<String, String> parentContent = new HashMap<>();
            for (ChunkRetrieveResult p : parents) {
                parentContent.put(p.getChunkId(), p.getContent());
            }
            for (SearchResult r : results) {
                if (StringUtils.isNotBlank(r.parentId) && parentContent.containsKey(r.parentId)) {
                    r.content = parentContent.get(r.parentId) + "\n" + r.content;
                }
            }
        } catch (Exception e) {
            log.warn("Parent backfill failed: {}", e.getMessage());
        }
    }
}
