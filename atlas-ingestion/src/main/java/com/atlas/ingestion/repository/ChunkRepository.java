package com.atlas.ingestion.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for chunk persistence.
 *
 * Single responsibility: CRUD + targeted queries on the chunks table.
 * Bulk pgvector insert (ON CONFLICT DO NOTHING) is handled by ChunkJdbcWriter.
 */
public interface ChunkRepository extends JpaRepository<ChunkEntity, UUID> {

    /**
     * Returns the document_hash for the most recently ingested chunk
     * matching the given url and version.
     *
     * Used at the start of ingestion to detect whether a document has changed.
     * If the stored hash matches the current file hash → skip the file entirely.
     * If it differs → delete all chunks for this url+version, then re-ingest.
     */
    @Query("""
            SELECT c.documentHash
            FROM ChunkEntity c
            WHERE c.url = :url AND c.version = :version
            ORDER BY c.ingestedAt DESC
            LIMIT 1
            """)
    Optional<String> findDocumentHash(@Param("url") String url,
                                      @Param("version") String version);

    /**
     * Deletes all chunks for a given url + version.
     *
     * Called when a document_hash mismatch is detected — the document changed,
     * so its old chunks are stale and must be removed before re-ingestion.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ChunkEntity c WHERE c.url = :url AND c.version = :version")
    int deleteByUrlAndVersion(@Param("url") String url,
                              @Param("version") String version);
}
