-- V4: Scope content_hash uniqueness to (content_hash, version)
--
-- Previously content_hash was unique across the whole table, which meant
-- identical content shared between versions (e.g. 1.0-GA and 1.1) could only
-- be stored once — leaving later versions silently incomplete.
--
-- Correct invariant: the same chunk content may exist once per version.
-- Idempotency is preserved — re-running the same version twice is still a no-op.

ALTER TABLE chunks DROP CONSTRAINT IF EXISTS chunks_content_hash_key;

ALTER TABLE chunks
    ADD CONSTRAINT chunks_content_hash_version_key UNIQUE (content_hash, version);
