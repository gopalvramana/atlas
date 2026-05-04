-- Atlas database schema
-- Apply once on first run; docker-compose mounts this as init script.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;  -- supports BM25-style trigram indexes

-- -----------------------------------------------------------------------
-- chunks: the ingested knowledge base
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chunks (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source       TEXT        NOT NULL,   -- 'spring-ai-docs' | 'spring-ai-github'
    version      TEXT        NOT NULL,   -- '0.8' | '1.0-GA' | '1.1+'
    section      TEXT,                   -- e.g. 'ChatClient', 'EmbeddingModel'
    url          TEXT,
    content      TEXT        NOT NULL,
    content_hash TEXT        NOT NULL UNIQUE,  -- SHA-256; used for idempotent upsert
    embedding    VECTOR(1536),           -- text-embedding-3-small (1536 dimensions)
    content_tsv  TSVECTOR    GENERATED ALWAYS AS
                     (to_tsvector('english', content)) STORED,
    ingested_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Semantic similarity index (IVFFlat; switch to HNSW for larger corpora)
CREATE INDEX IF NOT EXISTS idx_chunks_embedding
    ON chunks USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- Full-text search index (BM25 path)
CREATE INDEX IF NOT EXISTS idx_chunks_tsv
    ON chunks USING GIN (content_tsv);

-- Version filter index
CREATE INDEX IF NOT EXISTS idx_chunks_version
    ON chunks (version);

-- -----------------------------------------------------------------------
-- eval_runs: CI evaluation history
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS eval_runs (
    id        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    run_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    git_sha   TEXT,
    total     INT         NOT NULL,
    passed    INT         NOT NULL,
    failed    INT         NOT NULL,
    pass_rate NUMERIC(5,2) NOT NULL,  -- e.g. 84.00
    results   JSONB       NOT NULL    -- per-question detail
);
