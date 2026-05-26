package com.atlas.retrieval.model;

import com.atlas.core.Chunk;

/**
 * A chunk paired with its relevance score for a specific query.
 *
 * Single responsibility: carry a chunk and its score through the retrieval pipeline.
 *
 * The score field is intentionally generic — it holds different values at each stage:
 *   - After vector search:  cosine similarity score  (0.0 to 1.0, higher = more similar)
 *   - After BM25 search:    ts_rank score            (0.0+, higher = more relevant)
 *   - After RRF merge:      RRF combined score       (0.0 to 1.0, higher = better rank)
 *   - After reranking:      cross-encoder score      (0.0 to 1.0, higher = more relevant)
 *
 * Score is NOT stored in the DB — it is computed at query time and lives only in memory.
 */
public record ScoredChunk(Chunk chunk, double score) {

    /**
     * Convenience accessor — the chunk's unique content hash.
     * Used by HybridSearchService to deduplicate chunks that appear in both
     * the vector search results and the BM25 results.
     */
    public String contentHash() {
        return chunk.getContentHash();
    }
}
