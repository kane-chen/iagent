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
