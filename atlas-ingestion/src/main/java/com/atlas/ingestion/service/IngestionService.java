package com.atlas.ingestion.service;

import com.atlas.core.Chunk;
import com.atlas.core.ChunkSource;
import com.atlas.core.Version;
import com.atlas.ingestion.adapter.AsciiDocAdapter;
import com.atlas.ingestion.chunking.ChunkingService;
import com.atlas.ingestion.config.GitHubConfig;
import com.atlas.ingestion.embedding.EmbeddingService;
import com.atlas.ingestion.fetcher.FetchedDocument;
import com.atlas.ingestion.fetcher.GitHubDocsFetcher;
import com.atlas.ingestion.repository.ChunkJdbcWriter;
import com.atlas.ingestion.repository.ChunkRepository;
import com.atlas.ingestion.repository.IngestionRunEntity;
import com.atlas.ingestion.repository.IngestionRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the full ingestion pipeline for all configured versions.
 *
 * Single responsibility: coordinate Fetch → Extract → Chunk → Embed → Store.
 * Delegates each step to a dedicated single-purpose component.
 *
 * Pipeline per document:
 *   1. Compute document_hash from raw .adoc content
 *   2. If stored document_hash matches → skip (document unchanged)
 *   3. If mismatch → delete old chunks, then re-ingest
 *   4. Chunk → Embed → bulk insert (ON CONFLICT DO NOTHING for chunk-level safety)
 *
 * Writes one IngestionRunEntity per version per run for observability.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final GitHubDocsFetcher fetcher;
    private final AsciiDocAdapter asciiDocAdapter;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final ChunkRepository chunkRepository;
    private final ChunkJdbcWriter chunkJdbcWriter;
    private final IngestionRunRepository runRepository;
    private final GitHubConfig config;

    public IngestionService(GitHubDocsFetcher fetcher,
                            AsciiDocAdapter asciiDocAdapter,
                            ChunkingService chunkingService,
                            EmbeddingService embeddingService,
                            ChunkRepository chunkRepository,
                            ChunkJdbcWriter chunkJdbcWriter,
                            IngestionRunRepository runRepository,
                            GitHubConfig config) {
        this.fetcher = fetcher;
        this.asciiDocAdapter = asciiDocAdapter;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.chunkRepository = chunkRepository;
        this.chunkJdbcWriter = chunkJdbcWriter;
        this.runRepository = runRepository;
        this.config = config;
    }

    /**
     * Runs the full ingestion pipeline for every configured version.
     */
    public void ingestAll() {
        for (GitHubConfig.VersionConfig versionConfig : config.getVersions()) {
            Version version = Version.fromLabel(versionConfig.getLabel());
            ingestVersion(version);
        }
    }

    // -----------------------------------------------------------------------
    // private — per-version pipeline
    // -----------------------------------------------------------------------

    private void ingestVersion(Version version) {
        log.info("=== Ingesting version: {} (branch: {}) ===",
                version.getLabel(), version.getBranch());

        long startMs = System.currentTimeMillis();
        int filesFetched = 0;
        int chunksProduced = 0;
        int chunksInserted = 0;
        int chunksSkipped = 0;
        String status = "SUCCESS";
        String errorMessage = null;

        try {
            List<FetchedDocument> documents = fetcher.fetch(version.getBranch());
            filesFetched = documents.size();
            log.info("Fetched {} files for version {}", filesFetched, version.getLabel());

            for (FetchedDocument doc : documents) {
                DocumentResult result = processDocument(doc, version);
                chunksProduced += result.produced();
                chunksInserted += result.inserted();
                chunksSkipped  += result.skipped();
            }

        } catch (Exception e) {
            status = "FAILED";
            errorMessage = e.getMessage();
            log.error("Ingestion failed for version {}: {}", version.getLabel(), e.getMessage(), e);
        } finally {
            long durationMs = System.currentTimeMillis() - startMs;
            recordRun(version, filesFetched, chunksProduced,
                    chunksInserted, chunksSkipped, durationMs, status, errorMessage);

            log.info("Version {} done — files={}, produced={}, inserted={}, skipped={}, {}ms",
                    version.getLabel(), filesFetched, chunksProduced,
                    chunksInserted, chunksSkipped, durationMs);
        }
    }

    /**
     * Processes a single document through the pipeline.
     * Returns counts for metrics.
     */
    private DocumentResult processDocument(FetchedDocument doc, Version version) {
        String documentHash = ChunkingService.sha256(doc.rawContent());

        // --- Document-level idempotency check ---
        Optional<String> storedHash = chunkRepository.findDocumentHash(doc.url(), version.getLabel());

        if (storedHash.isPresent() && storedHash.get().equals(documentHash)) {
            log.debug("  SKIP {} — document unchanged (hash match)", doc.filename());
            // Count existing chunks as skipped — we don't re-embed them
            return new DocumentResult(0, 0, 1);
        }

        if (storedHash.isPresent()) {
            // Document changed — delete stale chunks before re-ingesting
            int deleted = chunkRepository.deleteByUrlAndVersion(doc.url(), version.getLabel());
            log.info("  CHANGED {} — deleted {} stale chunks", doc.filename(), deleted);
        }

        // --- Extract → Chunk → Embed → Store ---
        String plainText = asciiDocAdapter.extractText(doc.rawContent(), doc.url());
        String section   = doc.filename().replace(".adoc", "");

        List<Chunk> chunks = chunkingService.chunk(
                plainText, ChunkSource.SPRING_AI_GITHUB,
                version, section, doc.url(), documentHash
        );

        if (chunks.isEmpty()) {
            log.warn("  EMPTY {} — no chunks produced", doc.filename());
            return new DocumentResult(0, 0, 0);
        }

        List<Chunk> embedded = embeddingService.embed(chunks);
        int inserted = chunkJdbcWriter.insertAll(embedded);

        log.info("  ✓ {} → {} chunks produced, {} inserted", doc.filename(), embedded.size(), inserted);
        return new DocumentResult(embedded.size(), inserted, 0);
    }

    private void recordRun(Version version,
                           int filesFetched, int chunksProduced,
                           int chunksInserted, int chunksSkipped,
                           long durationMs, String status, String errorMessage) {
        try {
            IngestionRunEntity run = new IngestionRunEntity();
            run.setId(UUID.randomUUID());
            run.setRunAt(Instant.now());
            run.setSource(ChunkSource.SPRING_AI_GITHUB.getLabel());
            run.setVersion(version.getLabel());
            run.setBranch(version.getBranch());
            run.setFilesFetched(filesFetched);
            run.setChunksProduced(chunksProduced);
            run.setChunksInserted(chunksInserted);
            run.setChunksSkipped(chunksSkipped);
            run.setDurationMs(durationMs);
            run.setStatus(status);
            run.setErrorMessage(errorMessage);
            runRepository.save(run);
        } catch (Exception e) {
            log.error("Failed to record ingestion run for version {}: {}", version.getLabel(), e.getMessage());
        }
    }

    // Simple value carrier — no need for a separate class file
    private record DocumentResult(int produced, int inserted, int skipped) {}
}
