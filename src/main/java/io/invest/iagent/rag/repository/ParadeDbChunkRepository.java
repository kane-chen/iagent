package io.invest.iagent.rag.repository;

import io.invest.iagent.rag.model.TagCondition;
import io.invest.iagent.rag.model.TagFilter;
import io.invest.iagent.rag.model.TagOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 基于 PostgreSQL + pgvector(halfvec) + ParadeDB(BM25) 的 Chunk 仓储实现。
 */
@Component
@Slf4j
public class ParadeDbChunkRepository implements ChunkRepository {

    /** tag_key 白名单：仅允许字母数字下划线，作为 SQL 标识符的防御性校验（值始终走参数绑定） */
    private static final Pattern TAG_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

        // embedding 以 varchar 数组传入，再在 SELECT 中逐元素 ::halfvec；
        // 直接 varchar[]::halfvec[] PG 不支持（数组类型之间无显式 cast）
        String embeddingsSql = """
            INSERT INTO embeddings
                (source_id, source_type, chunk_id, knowledge_id,
                 knowledge_base_id, chunk_type, content, context_header,
                 dimension, embedding, parent_chunk_id, is_enabled)
            SELECT source_id, source_type, chunk_id, knowledge_id,
                   knowledge_base_id, chunk_type, content, context_header,
                   dimension, embedding::halfvec, parent_chunk_id, is_enabled
            FROM UNNEST(
                ?::varchar[], ?::integer[], ?::varchar[], ?::varchar[],
                ?::varchar[], ?::varchar[], ?::text[], ?::text[],
                ?::integer[], ?::varchar[], ?::varchar[], ?::boolean[]
            ) AS t(source_id, source_type, chunk_id, knowledge_id,
                   knowledge_base_id, chunk_type, content, context_header,
                   dimension, embedding, parent_chunk_id, is_enabled)
            """;

        jdbcTemplate.execute((ConnectionCallback<Void>) conn -> {
            boolean prevAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                // 1. 插入 embeddings
                try (var ps = conn.prepareStatement(embeddingsSql)) {
                    int n = entities.size();
                    ps.setArray(1, conn.createArrayOf("varchar", col(entities, ChunkDO::getSourceId)));
                    ps.setArray(2, conn.createArrayOf("integer",
                            entities.stream()
                                    .map(e -> e.getSourceType() != null ? e.getSourceType() : 0)
                                    .toArray()));
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
                // 2. 插入标签（同一事务）
                insertTags(conn, entities);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(prevAutoCommit);
            }
            return null;
        });
        log.debug("Batch saved {} chunks", entities.size());
    }

    /** 把所有非空 tags 展平为 4 个并行数组批量插入 chunk_tags。 */
    private void insertTags(Connection conn, List<ChunkDO> entities) throws SQLException {
        List<String> kbIds = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (ChunkDO e : entities) {
            if (e.getTags() == null || e.getTags().isEmpty()) continue;
            for (Map.Entry<String, String> t : e.getTags().entrySet()) {
                if (t.getKey() == null || t.getValue() == null) continue;
                kbIds.add(e.getKnowledgeBaseId());
                chunkIds.add(e.getChunkId());
                keys.add(t.getKey());
                values.add(t.getValue());
            }
        }
        if (kbIds.isEmpty()) return;

        String tagsSql = """
            INSERT INTO chunk_tags (knowledge_base_id, chunk_id, tag_key, tag_value)
            SELECT knowledge_base_id, chunk_id, tag_key, tag_value
            FROM UNNEST(?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[])
                AS t(knowledge_base_id, chunk_id, tag_key, tag_value)
            ON CONFLICT (knowledge_base_id, chunk_id, tag_key, tag_value) DO NOTHING
            """;
        try (var ps = conn.prepareStatement(tagsSql)) {
            ps.setArray(1, conn.createArrayOf("varchar", kbIds.toArray()));
            ps.setArray(2, conn.createArrayOf("varchar", chunkIds.toArray()));
            ps.setArray(3, conn.createArrayOf("varchar", keys.toArray()));
            ps.setArray(4, conn.createArrayOf("varchar", values.toArray()));
            ps.executeUpdate();
        }
    }

    // =========================================================
    //  删除
    // =========================================================

    @Override
    public void deleteByKnowledgeId(String knowledgeBaseId, String knowledgeId) {
        String sql = "DELETE FROM embeddings WHERE knowledge_base_id = ? AND knowledge_id = ?";
        int n = jdbcTemplate.update(sql, knowledgeBaseId, knowledgeId);
        log.debug("Deleted {} chunks by knowledgeId (kb={}, kid={})", n, knowledgeBaseId, knowledgeId);
    }

    // =========================================================
    //  检索
    // =========================================================

    @Override
    public List<ChunkRetrieveResult> keywordSearch(ChunkRetrieveParams params) {
        // 使用 IN(?,?,...)：jdbcTemplate.query 的 Object... 会把 String[] 展开成多个位置参数，
        // 导致 = ANY(?) 只拿到第一个字符串（scalar），报 "requires array on right side"
        String kbPlaceholders = params.getKnowledgeBaseIds().stream()
                .map(id -> "?").collect(Collectors.joining(","));

        StringBuilder sql = new StringBuilder("""
            SELECT e.id, e.source_id, e.chunk_id, e.knowledge_id, e.knowledge_base_id,
                   e.chunk_type, e.context_header, e.parent_chunk_id, e.content,
                   paradedb.score(e.id) AS score
            FROM embeddings e
            WHERE e.knowledge_base_id IN (%s)
              AND e.is_enabled = true
              AND e.content @@@ paradedb.match('content', ?)
            """.formatted(kbPlaceholders));
        List<Object> args = new ArrayList<>();
        args.addAll(params.getKnowledgeBaseIds());
        args.add(params.getQuery());
        // 标签过滤（关联到外层 embeddings e）
        appendTagFilter(sql, args, params.getTagFilter(), "e.knowledge_base_id", "e.chunk_id");
        sql.append(" ORDER BY score DESC LIMIT ?");
        args.add(params.getTopK());

        List<ChunkRetrieveResult> results = jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> mapResult(rs, rs.getDouble("score"), "keyword"),
                args.toArray());
        attachTags(results, params.getKnowledgeBaseIds());
        return results;
    }

    @Override
    public List<ChunkRetrieveResult> vectorSearch(ChunkRetrieveParams params) {
        if (params.getQueryEmbedding() == null || params.getQueryEmbedding().length == 0) {
            return Collections.emptyList();
        }

        String vecLiteral = toHalfVecLiteral(params.getQueryEmbedding());
        String kbPlaceholders = params.getKnowledgeBaseIds().stream()
                .map(id -> "?").collect(Collectors.joining(","));

        StringBuilder sql = new StringBuilder("""
            SELECT e.id, e.source_id, e.chunk_id, e.knowledge_id, e.knowledge_base_id,
                   e.chunk_type, e.context_header, e.parent_chunk_id, e.content,
                   (1 - (e.embedding <=> ?::halfvec)) AS score
            FROM embeddings e
            WHERE e.knowledge_base_id IN (%s)
              AND e.is_enabled = true
              AND e.dimension = ?
            """.formatted(kbPlaceholders));
        List<Object> args = new ArrayList<>();
        args.add(vecLiteral);
        args.addAll(params.getKnowledgeBaseIds());
        args.add(params.getQueryEmbedding().length);
        appendTagFilter(sql, args, params.getTagFilter(), "e.knowledge_base_id", "e.chunk_id");
        sql.append(" ORDER BY e.embedding <=> ?::halfvec LIMIT ?");
        args.add(vecLiteral);
        args.add(params.getTopK());

        List<ChunkRetrieveResult> results = jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> mapResult(rs, rs.getDouble("score"), "vector"),
                args.toArray());
        attachTags(results, params.getKnowledgeBaseIds());
        return results;
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

        // 使用 IN(?,?,...) 而非 = ANY(?)：JDBC 直接绑定 String[] 不会被 pgjdbc 转成 PG array，
        // 会抛 "op ANY/ALL (array) requires array on right side"
        String placeholders = chunkIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = """
            SELECT id, source_id, chunk_id, knowledge_id, knowledge_base_id,
                   chunk_type, context_header, parent_chunk_id, content, 0.0 AS score
            FROM embeddings
            WHERE chunk_id IN (%s)
            """.formatted(placeholders);

        List<ChunkRetrieveResult> results = jdbcTemplate.query(sql,
                (rs, rowNum) -> mapResult(rs, 0.0, "lookup"),
                chunkIds.toArray(new String[0]));
        // 回填标签（不限定 kb，按 (kb, chunkId) 匹配）
        attachTags(results, null);
        return results;
    }

    @Override
    public List<String> findTagValues(String knowledgeBaseId, String tagKey, TagFilter scope) {
        validateTagKey(tagKey);
        StringBuilder sql = new StringBuilder("""
            SELECT DISTINCT ct0.tag_value
            FROM chunk_tags ct0
            WHERE ct0.knowledge_base_id = ? AND ct0.tag_key = ?
            """);
        List<Object> args = new ArrayList<>();
        args.add(knowledgeBaseId);
        args.add(tagKey);
        // scope 过滤关联到外层 ct0
        appendTagFilter(sql, args, scope, "ct0.knowledge_base_id", "ct0.chunk_id");
        sql.append(" ORDER BY ct0.tag_value");
        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> rs.getString("tag_value"),
                args.toArray());
    }

    // =========================================================
    //  标签过滤 / 回填
    // =========================================================

    /**
     * 把 {@link TagFilter} 拼接到 SQL：每个 condition 生成一个关联外层行的 EXISTS 子查询，
     * 条件之间为 AND。IN 用显式占位符（规避 pgjdbc = ANY(?) 数组陷阱）。
     *
     * @param outerKbExpr    外层表 knowledge_base_id 列表达式（如 "e.knowledge_base_id"）
     * @param outerChunkExpr 外层表 chunk_id 列表达式（如 "e.chunk_id"）
     */
    static void appendTagFilter(StringBuilder sql, List<Object> args,
                                TagFilter filter, String outerKbExpr, String outerChunkExpr) {
        if (filter == null || filter.isEmpty()) return;
        for (TagCondition c : filter.getConditions()) {
            if (c == null || c.getKey() == null || c.getValues() == null || c.getValues().isEmpty()) {
                continue;
            }
            validateTagKey(c.getKey());
            sql.append(" AND EXISTS (SELECT 1 FROM chunk_tags ct_f")
                    .append(filter.getConditions().indexOf(c))
                    .append(" WHERE ct_f").append(filter.getConditions().indexOf(c))
                    .append(".knowledge_base_id = ").append(outerKbExpr)
                    .append(" AND ct_f").append(filter.getConditions().indexOf(c))
                    .append(".chunk_id = ").append(outerChunkExpr)
                    .append(" AND ct_f").append(filter.getConditions().indexOf(c))
                    .append(".tag_key = ?");
            args.add(c.getKey());
            if (c.getOperator() == TagOperator.IN) {
                String ph = c.getValues().stream().map(v -> "?").collect(Collectors.joining(","));
                sql.append(" AND ct_f").append(filter.getConditions().indexOf(c))
                        .append(".tag_value IN (").append(ph).append(")");
                args.addAll(c.getValues());
            } else {
                sql.append(" AND ct_f").append(filter.getConditions().indexOf(c))
                        .append(".tag_value = ?");
                args.add(c.getValues().get(0));
            }
            sql.append(")");
        }
    }

    private static void validateTagKey(String key) {
        if (key == null || !TAG_KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("Illegal tag key: " + key);
        }
    }

    /**
     * 批量回填标签到结果集（单次查询，避免 N+1）。
     *
     * @param kbIds 限定的知识库集合；null/空表示不限定（用于 findByChunkIds）
     */
    private void attachTags(List<ChunkRetrieveResult> results, List<String> kbIds) {
        if (results == null || results.isEmpty()) return;
        Set<String> chunkIds = results.stream()
                .map(ChunkRetrieveResult::getChunkId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (chunkIds.isEmpty()) return;

        String chunkPh = chunkIds.stream().map(id -> "?").collect(Collectors.joining(","));
        StringBuilder sql = new StringBuilder(
                "SELECT knowledge_base_id, chunk_id, tag_key, tag_value FROM chunk_tags WHERE chunk_id IN ("
                        + chunkPh + ")");
        List<Object> args = new ArrayList<>(chunkIds);
        if (kbIds != null && !kbIds.isEmpty()) {
            String kbPh = kbIds.stream().map(id -> "?").collect(Collectors.joining(","));
            sql.append(" AND knowledge_base_id IN (").append(kbPh).append(")");
            args.addAll(kbIds);
        }

        // key = kbId + '\0' + chunkId
        Map<String, Map<String, String>> tagsByChunk = new HashMap<>();
        jdbcTemplate.query(sql.toString(), rs -> {
            String key = rs.getString("knowledge_base_id") + '\0' + rs.getString("chunk_id");
            tagsByChunk.computeIfAbsent(key, k -> new HashMap<>())
                    .put(rs.getString("tag_key"), rs.getString("tag_value"));
        }, args.toArray());

        for (ChunkRetrieveResult r : results) {
            Map<String, String> tags = tagsByChunk.get(r.getKnowledgeBaseId() + '\0' + r.getChunkId());
            if (tags != null) {
                r.setTags(tags);
            }
        }
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
        ChunkRetrieveResult getPrototype() { return prototype; }
    }
}
