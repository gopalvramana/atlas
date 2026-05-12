-- V2: Add ingestion_runs table
-- Tracks each ingestion run — source, version, outcome, duration.

CREATE TABLE IF NOT EXISTS ingestion_runs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    run_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    source          TEXT         NOT NULL,   -- 'spring-ai-github'
    version         TEXT         NOT NULL,   -- '0.8' | '1.0-GA' | '1.1+'
    branch          TEXT         NOT NULL,   -- '0.8.x' | '1.0.x' | '1.1.x'
    files_fetched   INT,
    chunks_produced INT,
    chunks_inserted INT,
    chunks_skipped  INT,
    duration_ms     BIGINT,
    status          TEXT         NOT NULL,   -- 'SUCCESS' | 'FAILED'
    error_message   TEXT                     -- populated if status = 'FAILED'
);
