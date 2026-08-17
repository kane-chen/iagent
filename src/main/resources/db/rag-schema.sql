-- RAG 模块数据库 Schema
-- 需要 PostgreSQL + pgvector + ParadeDB(pg_search) 扩展

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_search;

CREATE TABLE IF NOT EXISTS embeddings (
    id               BIGSERIAL PRIMARY KEY,
    source_id        VARCHAR(128),
    source_type      INTEGER NOT NULL DEFAULT 0,
    chunk_id         VARCHAR(64) NOT NULL,
    knowledge_id     VARCHAR(64),
    knowledge_base_id VARCHAR(64) NOT NULL,
    chunk_type       VARCHAR(32) NOT NULL DEFAULT 'text',
    content          TEXT NOT NULL,
    context_header   TEXT,
    dimension        INTEGER NOT NULL,
    embedding        halfvec(2560),
    parent_chunk_id  VARCHAR(64),
    is_enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP NOT NULL DEFAULT now()
);

-- ParadeDB BM25 全文检索索引（chinese_lindera 中文分词）
CREATE INDEX IF NOT EXISTS embeddings_search_idx ON embeddings
    USING bm25 (id, knowledge_base_id, content, chunk_type)
    WITH (key_field='id', text_fields='{"content":{"tokenizer":{"type":"chinese_lindera"}}}');

-- HNSW 向量索引（余弦距离）
CREATE INDEX IF NOT EXISTS embeddings_hnsw_idx ON embeddings
    USING hnsw (embedding halfvec_cosine_ops);

-- 过滤索引
CREATE INDEX IF NOT EXISTS embeddings_kb_idx ON embeddings (knowledge_base_id);
CREATE INDEX IF NOT EXISTS embeddings_parent_idx ON embeddings (parent_chunk_id)
    WHERE parent_chunk_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS embeddings_chunk_uk ON embeddings (knowledge_base_id, chunk_id);

-- =========================================================
--  Chunk 标签（KV）：一个 chunk 可有多个标签；检索支持 EQ/IN + 跨 key AND
-- =========================================================
CREATE TABLE IF NOT EXISTS chunk_tags (
    id                BIGSERIAL PRIMARY KEY,
    knowledge_base_id VARCHAR(64)  NOT NULL,
    chunk_id          VARCHAR(64)  NOT NULL,
    tag_key           VARCHAR(64)  NOT NULL,
    tag_value         VARCHAR(512) NOT NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    -- 复用 embeddings(knowledge_base_id, chunk_id) 唯一索引；删除 embedding 时标签级联删除
    CONSTRAINT chunk_tags_chunk_fk
        FOREIGN KEY (knowledge_base_id, chunk_id)
        REFERENCES embeddings (knowledge_base_id, chunk_id) ON DELETE CASCADE,
    CONSTRAINT chunk_tags_uk UNIQUE (knowledge_base_id, chunk_id, tag_key, tag_value)
);

-- 过滤主索引：kb + key + value 覆盖 EQ/IN
CREATE INDEX IF NOT EXISTS chunk_tags_kv_idx
    ON chunk_tags (knowledge_base_id, tag_key, tag_value);
-- DISTINCT tag_value（周期归一化取“最新期间”等）
CREATE INDEX IF NOT EXISTS chunk_tags_key_idx
    ON chunk_tags (knowledge_base_id, tag_key);
-- 按 chunk 回填/清理
CREATE INDEX IF NOT EXISTS chunk_tags_chunk_idx
    ON chunk_tags (knowledge_base_id, chunk_id);
