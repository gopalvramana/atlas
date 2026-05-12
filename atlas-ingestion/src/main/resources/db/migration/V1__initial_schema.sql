-- V1: Initial schema
-- Creates chunks and eval_runs tables with all indexes.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- -----------------------------------------------------------------------
-- chunks: the ingested knowledge base
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chunks (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    source       TEXT        NOT NULL,        -- 'spring-ai-docs' | 'spring-ai-github'
    version      TEXT        NOT NULL,        -- '0.8' | '1.0-GA' | '1.1+'
    section      TEXT,                        -- e.g. 'ChatClient', 'EmbeddingModel'
    url          TEXT,
    content      TEXT        NOT NULL,
    content_hash TEXT        NOT NULL UNIQUE, -- SHA-256; used for idempotent upsert
    embedding    VECTOR(1536),                -- text-embedding-3-small output
    content_tsv  TSVECTOR    GENERATED ALWAYS AS
                     (to_tsvector('english', content)) STORED,
    ingested_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chunks_embedding
    ON chunks USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

CREATE INDEX IF NOT EXISTS idx_chunks_tsv
    ON chunks USING GIN (content_tsv);

CREATE INDEX IF NOT EXISTS idx_chunks_version
    ON chunks (version);

-- -----------------------------------------------------------------------
-- eval_runs: CI evaluation history
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS eval_runs (
    id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    run_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    git_sha   TEXT,
    total     INT          NOT NULL,
    passed    INT          NOT NULL,
    failed    INT          NOT NULL,
    pass_rate NUMERIC(5,2) NOT NULL,
    results   JSONB        NOT NULL
);
