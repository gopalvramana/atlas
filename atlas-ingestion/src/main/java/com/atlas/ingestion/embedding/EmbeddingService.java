package com.atlas.ingestion.embedding;

import com.atlas.core.Chunk;
import com.atlas.ingestion.config.IngestionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Populates the embedding field on each Chunk by calling OpenAI text-embedding-3-small.
 *
 * Single responsibility: call OpenAI embedding API in batches and set embedding on each Chunk.
 * No fetching, no chunking, no DB access.
 *
 * Retry strategy: exponential backoff on HTTP 429 (rate limit), up to 3 attempts.
 * Batch size: configured via atlas.ingestion.batch-size (default 100).
 */
@Component
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 2000;

    private final EmbeddingModel embeddingModel;
    private final IngestionConfig config;

    public EmbeddingService(EmbeddingModel embeddingModel, IngestionConfig config) {
        this.embeddingModel = embeddingModel;
        this.config = config;
    }

    /**
     * Embeds all chunks in batches. Mutates each Chunk in place — sets the embedding field.
     *
     * @param chunks list of chunks with content filled, embedding null
     * @return same list with embedding populated on every chunk
     */
    public List<Chunk> embed(List<Chunk> chunks) {
        if (chunks.isEmpty()) return chunks;

        int batchSize = config.getBatchSize();
        int total = chunks.size();
        int batchCount = (int) Math.ceil((double) total / batchSize);

        log.info("Embedding {} chunks in {} batch(es) of up to {}", total, batchCount, batchSize);

        for (int i = 0; i < total; i += batchSize) {
            int batchNum = (i / batchSize) + 1;
            List<Chunk> batch = chunks.subList(i, Math.min(i + batchSize, total));

            log.info("Embedding batch {}/{} — chunks {}-{}", batchNum, batchCount, i + 1, i + batch.size());

            embedBatchWithRetry(batch, batchNum, batchCount);
        }

        return chunks;
    }

    // --- private helpers ---

    private void embedBatchWithRetry(List<Chunk> batch, int batchNum, int batchCount) {
        long backoffMs = INITIAL_BACKOFF_MS;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                embedBatch(batch);
                return; // success

            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                boolean isRateLimit = message.contains("429") || message.contains("rate limit");

                if (attempt == MAX_RETRIES) {
                    log.error("Embedding batch {}/{} failed after {} attempts: {}",
                            batchNum, batchCount, MAX_RETRIES, message);
                    throw new EmbeddingException("Embedding failed after " + MAX_RETRIES + " attempts", e);
                }

                if (isRateLimit) {
                    log.warn("Rate limited (HTTP 429) on batch {}/{} — attempt {}/{}, backing off {}ms",
                            batchNum, batchCount, attempt, MAX_RETRIES, backoffMs);
                } else {
                    log.warn("Embedding error on batch {}/{} — attempt {}/{}: {}",
                            batchNum, batchCount, attempt, MAX_RETRIES, message);
                }

                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new EmbeddingException("Interrupted during backoff", ie);
                }

                backoffMs *= 2; // exponential backoff
            }
        }
    }

    private void embedBatch(List<Chunk> batch) {
        List<String> texts = batch.stream()
                .map(Chunk::getContent)
                .toList();

        EmbeddingResponse response = embeddingModel.embedForResponse(texts);

        List<float[]> vectors = response.getResults().stream()
                .map(result -> result.getOutput())
                .toList();

        if (vectors.size() != batch.size()) {
            throw new EmbeddingException(
                    "OpenAI returned " + vectors.size() + " vectors for " + batch.size() + " chunks");
        }

        // Mutate in place — set embedding on each chunk
        for (int i = 0; i < batch.size(); i++) {
            batch.get(i).setEmbedding(vectors.get(i));
        }
    }
}
