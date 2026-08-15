package io.invest.iagent.rag.retrieve.plugins;

import io.invest.iagent.rag.repository.ChunkRepository;
import io.invest.iagent.rag.repository.ChunkRetrieveResult;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.PluginErrorOrNone;
import io.invest.iagent.rag.retrieve.dto.PluginException;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.model.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * CHUNK_MERGE：parent 回填、去重、相邻合并
 */
@Slf4j
public class MergePlugin implements Plugin {

    private final ChunkRepository chunkRepository;

    public MergePlugin(ChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    @Override
    public List<EventType> activationEvents() {
        return Collections.singletonList(EventType.CHUNK_MERGE);
    }

    @Override
    public PluginErrorOrNone onEvent(PipelineContext ctx, EventType eventType, ChatManage cm,
                                     Supplier<PluginErrorOrNone> next) throws PluginException {
        if (!cm.needsRetrieval()) return next.get();

        // 修复：优先使用 rerankResult，否则使用 searchResult
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
        return next.get();
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
