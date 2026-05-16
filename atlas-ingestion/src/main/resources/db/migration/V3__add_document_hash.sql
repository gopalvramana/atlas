-- V3: Add document_hash column to chunks table
-- document_hash is SHA-256 of the full source document content.
-- Used for document-level idempotency:
--   unchanged document → skip all chunks for that file
--   changed document   → delete old chunks, insert new ones

ALTER TABLE chunks ADD COLUMN IF NOT EXISTS document_hash TEXT;

CREATE INDEX IF NOT EXISTS idx_chunks_document_hash
    ON chunks (document_hash);

CREATE INDEX IF NOT EXISTS idx_chunks_url_version
    ON chunks (url, version);
