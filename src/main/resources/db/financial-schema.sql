-- ============================================================
-- 财务数据服务表结构（PostgreSQL，复用 iagent 库）
-- 普通业务表：btree 索引即可，不需要 pgvector / ParadeDB。
-- 全部 CREATE TABLE IF NOT EXISTS，模块启用时自动初始化，可重复执行。
-- ============================================================

-- 公司
CREATE TABLE IF NOT EXISTS fin_company (
    ticker       VARCHAR(16) PRIMARY KEY,   -- 00700 / BABA
    market       VARCHAR(8)  NOT NULL,      -- HK / US / CN
    name         VARCHAR(128),
    currency     VARCHAR(8),                -- HKD / USD / RMB
    fy_end_month INT DEFAULT 12,           -- 财年结束月份
    updated_at   TIMESTAMP NOT NULL DEFAULT now()
);

-- 指标值（三大表 + 派生指标 + RAG 补充指标）
CREATE TABLE IF NOT EXISTS fin_metric_value (
    id            BIGSERIAL PRIMARY KEY,
    ticker        VARCHAR(16) NOT NULL,
    fiscal_period VARCHAR(16) NOT NULL,     -- 2026Q2 / FY2025
    period_type   VARCHAR(16) NOT NULL,     -- SINGLE_Q / CUMULATIVE / FY
    metric_code   VARCHAR(64) NOT NULL,     -- 标准指标编码（见 metric-catalog.yml）
    value         NUMERIC(24,6),            -- 统一百万单位；NULL 表示该公司不披露
    yoy           NUMERIC(12,4),            -- 同比 %
    qoq           NUMERIC(12,4),            -- 环比 %
    currency      VARCHAR(8),
    unit          VARCHAR(16) NOT NULL DEFAULT 'million',
    source        VARCHAR(16) NOT NULL,     -- FUTU_API / RAG / DERIVED
    confidence    INT,                      -- RAG 提取置信度（0-100）
    document_id   VARCHAR(128),             -- 溯源：财报目录 documentId
    chunk_id      VARCHAR(64),              -- 溯源：RAG chunk
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP NOT NULL DEFAULT now()
);
-- 同一公司/期间/口径/指标/来源唯一，支持幂等 upsert
-- （港股/A股累计值 CUMULATIVE 与差分单季值 SINGLE_Q 同期间标签共存；source 不同允许共存，便于交叉核对）
CREATE UNIQUE INDEX IF NOT EXISTS fin_mv_uk
    ON fin_metric_value (ticker, fiscal_period, period_type, metric_code, source);
CREATE INDEX IF NOT EXISTS fin_mv_query_idx
    ON fin_metric_value (ticker, metric_code, fiscal_period);

-- 分部目录（公司特定维度）
CREATE TABLE IF NOT EXISTS fin_segment (
    id           BIGSERIAL PRIMARY KEY,
    ticker       VARCHAR(16) NOT NULL,
    segment_code VARCHAR(64) NOT NULL,
    segment_name VARCHAR(256),
    parent_code  VARCHAR(64),               -- 父分部编码，null 为一级分部
    level        INT NOT NULL DEFAULT 1,
    sort_order   INT NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (ticker, segment_code)
);

-- 分部指标值
CREATE TABLE IF NOT EXISTS fin_segment_value (
    id            BIGSERIAL PRIMARY KEY,
    ticker        VARCHAR(16) NOT NULL,
    fiscal_period VARCHAR(16) NOT NULL,
    segment_code  VARCHAR(64) NOT NULL,
    metric_code   VARCHAR(64) NOT NULL,     -- REVENUE / OPERATING_INCOME / ADJUSTED_EBITA ...
    value         NUMERIC(24,6),
    yoy           NUMERIC(12,4),
    currency      VARCHAR(8),
    unit          VARCHAR(16) NOT NULL DEFAULT 'million',
    source        VARCHAR(16) NOT NULL DEFAULT 'SEGMENT_PARSE',
    confidence    INT,
    document_id   VARCHAR(128),
    updated_at    TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (ticker, fiscal_period, segment_code, metric_code)
);
CREATE INDEX IF NOT EXISTS fin_sv_query_idx
    ON fin_segment_value (ticker, fiscal_period);

-- 采集批次（便于排查与审计）
CREATE TABLE IF NOT EXISTS fin_ingest_batch (
    id         BIGSERIAL PRIMARY KEY,
    ticker     VARCHAR(16) NOT NULL,
    source     VARCHAR(16) NOT NULL,        -- FUTU_API / SEGMENT_PARSE / RAG
    status     VARCHAR(16) NOT NULL,        -- SUCCESS / PARTIAL / FAILED
    periods    TEXT,                        -- 本次覆盖的期间
    report     TEXT,                        -- 明细（JSON）
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS fin_batch_ticker_idx
    ON fin_ingest_batch (ticker, created_at);
