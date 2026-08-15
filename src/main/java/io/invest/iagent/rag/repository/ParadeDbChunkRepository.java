package io.invest.iagent.rag.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 基于 PostgreSQL + pgvector(halfvec) + ParadeDB(BM25) 的 Chunk 仓储实现。
 */
@Slf4j
public class ParadeDbChunkRepository implements ChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    public ParadeDbChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // =========================================================
    //  写入
    // =========================================================

    @Override
    public void save(ChunkDO entity) {
        String sql = """
            INSERT INTO embeddings
                (source_id, source_type, chunk_id, knowledge_id,
                 knowledge_base_id, chunk_type, content, context_header,
                 dimension, embedding, parent_chunk_id, is_enabled)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::halfvec, ?, ?)
            """;
        jdbcTemplate.update(sql,
                entity.getSourceId(), entity.getSourceType(), entity.getChunkId(),
                entity.getKnowledgeId(), entity.getKnowledgeBaseId(),
                entity.getChunkType() != null ? entity.getChunkType() : "text",
                entity.getContent(), entity.getContextHeader(),
                entity.getDimension(), toHalfVecLiteral(entity.getEmbedding()),
                entity.getParentChunkId(),
                entity.getIsEnabled() != null ? entity.getIsEnabled() : Boolean.TRUE);
    }

    @Override
    public void batchSave(List<ChunkDO> entities) {
        if (entities == null || entities.isEmpty()) return;

        String sql = """
            INSERT INTO embeddings
                (source_id, source_type, chunk_id, knowledge_id,
                 knowledge_base_id, chunk_type, content, context_header,
                 dimension, embedding, parent_chunk_id, is_enabled)
            SELECT * FROM UNNEST(
                ?::varchar[], ?::integer[], ?::varchar[], ?::varchar[],
                ?::varchar[], ?::varchar[], ?::text[], ?::text[],
                ?::integer[], ?::halfvec[], ?::varchar[], ?::boolean[]
            )
            """;

        jdbcTemplate.execute((ConnectionCallback<Void>) conn -> {
            int n = entities.size();
            try (var ps = conn.prepareStatement(sql)) {
                ps.setArray(1, conn.createArrayOf("varchar", col(entities, ChunkDO::getSourceId)));
                ps.setArray(2, conn.createArrayOf("integer", col(entities, ChunkDO::getSourceType)));
                ps.setArray(3, conn.createArrayOf("varchar", col(entities, ChunkDO::getChunkId)));
                ps.setArray(4, conn.createArrayOf("varchar", col(entities, ChunkDO::getKnowledgeId)));
                ps.setArray(5, conn.createArrayOf("varchar", col(entities, ChunkDO::getKnowledgeBaseId)));
                ps.setArray(6, conn.createArrayOf("varchar",
                        entities.stream().map(e -> e.getChunkType() != null ? e.getChunkType() : "text").toArray()));
                ps.setArray(7, conn.createArrayOf("text", col(entities, ChunkDO::getContent)));
                ps.setArray(8, conn.createArrayOf("text", col(entities, ChunkDO::getContextHeader)));
                ps.setArray(9, conn.createArrayOf("integer", col(entities, ChunkDO::getDimension)));
                ps.setArray(10, conn.createArrayOf("varchar",
                        entities.stream().map(e -> toHalfVecLiteral(e.getEmbedding())).toArray()));
                ps.setArray(11, conn.createArrayOf("varchar", col(entities, ChunkDO::getParentChunkId)));
                ps.setArray(12, conn.createArrayOf("boolean",
                        entities.stream().map(e -> e.getIsEnabled() != null ? e.getIsEnabled() : Boolean.TRUE).toArray()));
                ps.executeUpdate();
            }
            return null;
        });
        log.debug("Batch saved {} chunks", entities.size());
    }

    // =========================================================
    //  检索
    // =========================================================

    @Override
    public List<ChunkRetrieveResult> keywordSearch(ChunkRetrieveParams params) {
        String sql = """
            SELECT id, source_id, chunk_id, knowledge_id, knowledge_base_id,
                   chunk_type, context_header, parent_chunk_id, content,
                   paradedb.score(id) AS score
            FROM embeddings
            WHERE knowledge_base_id = ANY(?)
              AND is_enabled = true
              AND content @@@ paradedb.match('content', ?)
            ORDER BY score DESC
            LIMIT ?
            """;

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> mapResult(rs, rs.getDouble("score"), "keyword"),
                params.getKnowledgeBaseIds().toArray(new String[0]),
                params.getQuery(),
                params.getTopK());
    }

    @Override
    public List<ChunkRetrieveResult> vectorSearch(ChunkRetrieveParams params) {
        if (params.getQueryEmbedding() == null || params.getQueryEmbedding().length == 0) {
            return Collections.emptyList();
        }

        String vecLiteral = toHalfVecLiteral(params.getQueryEmbedding());
        String sql = """
            SELECT id, source_id, chunk_id, knowledge_id, knowledge_base_id,
                   chunk_type, context_header, parent_chunk_id, content,
                   (1 - (embedding <=> ?::halfvec)) AS score
            FROM embeddings
            WHERE knowledge_base_id = ANY(?)
              AND is_enabled = true
              AND dimension = ?
            ORDER BY embedding <=> ?::halfvec
            LIMIT ?
            """;

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> mapResult(rs, rs.getDouble("score"), "vector"),
                vecLiteral,
                params.getKnowledgeBaseIds().toArray(new String[0]),
                params.getQueryEmbedding().length,
                vecLiteral,
                params.getTopK());
    }

    @Override
    public List<ChunkRetrieveResult> rrfFuse(
            List<ChunkRetrieveResult> keywordResults,
            List<ChunkRetrieveResult> vectorResults,
            ChunkRetrieveParams params) {

        int k = params.getRrfK();
        Map<String, RrfAccumulator> accumMap = new ConcurrentHashMap<>();

        for (int i = 0; i < keywordResults.size(); i++) {
            ChunkRetrieveResult r = keywordResults.get(i);
            String key = r.getChunkId() != null ? r.getChunkId() : r.getSourceId();
            accumMap.computeIfAbsent(key, id -> new RrfAccumulator(r))
                    .addScore(params.getRrfKeywordWeight() / (k + i + 1));
        }

        for (int i = 0; i < vectorResults.size(); i++) {
            ChunkRetrieveResult r = vectorResults.get(i);
            String key = r.getChunkId() != null ? r.getChunkId() : r.getSourceId();
            accumMap.computeIfAbsent(key, id -> new RrfAccumulator(r))
                    .addScore(params.getRrfVectorWeight() / (k + i + 1));
        }

        return accumMap.values().stream()
                .sorted(Comparator.comparingDouble(RrfAccumulator::getScore).reversed())
                .limit(params.getTopK())
                .map(acc -> {
                    ChunkRetrieveResult r = acc.getPrototype();
                    r.setScore(acc.getScore());
                    r.setMatchType("hybrid");
                    return r;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<ChunkRetrieveResult> findByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return Collections.emptyList();

        String sql = """
            SELECT id, source_id, chunk_id, knowledge_id, knowledge_base_id,
                   chunk_type, context_header, parent_chunk_id, content, 0.0 AS score
            FROM embeddings
            WHERE chunk_id = ANY(?)
            """;

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> mapResult(rs, 0.0, "lookup"),
                chunkIds.toArray(new String[0]));
    }

    // =========================================================
    //  工具方法
    // =========================================================

    private String toHalfVecLiteral(float[] vec) {
        if (vec == null || vec.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private <T> Object[] col(List<ChunkDO> list, java.util.function.Function<ChunkDO, T> getter) {
        return list.stream().map(getter).toArray();
    }

    private ChunkRetrieveResult mapResult(ResultSet rs, double score, String matchType) throws SQLException {
        return ChunkRetrieveResult.builder()
                .id(rs.getString("id"))
                .sourceId(rs.getString("source_id"))
                .chunkId(rs.getString("chunk_id"))
                .knowledgeId(rs.getString("knowledge_id"))
                .knowledgeBaseId(rs.getString("knowledge_base_id"))
                .chunkType(rs.getString("chunk_type"))
                .contextHeader(rs.getString("context_header"))
                .parentChunkId(rs.getString("parent_chunk_id"))
                .content(rs.getString("content"))
                .score(score)
                .matchType(matchType)
                .build();
    }

    private static class RrfAccumulator {
        private final ChunkRetrieveResult prototype;
        private double score;

        RrfAccumulator(ChunkRetrieveResult prototype) {
            this.prototype = prototype;
        }

        void addScore(double delta) { this.score += delta; }
        double getScore() { return this.score; }
        ChunkRetrieveResult getPrototype() { return this.prototype; }
    }
}
