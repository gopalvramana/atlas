package com.atlas.retrieval.service;

import com.atlas.retrieval.model.ScoredChunk;
import com.atlas.retrieval.repository.BM25SearchRepository;
import com.atlas.retrieval.repository.VectorSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines vector search and BM25 search using Reciprocal Rank Fusion (RRF).
 *
 * Single responsibility: given a question, return a merged, deduplicated,
 * RRF-ranked list of candidate chunks for the reranker.
 *
 * Pipeline:
 *   1. Embed the question once (shared vector for both searches)
 *   2. Run vector search  → top-20 by cosine similarity
 *   3. Run BM25 search    → top-20 by keyword relevance
 *   4. Merge via RRF      → deduplicate + combine ranks → top-40
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    /**
     * RRF constant k = 60.
     * Dampens the advantage of being ranked #1.
     * Rank 1  → 1/(1+60)  = 0.0164
     * Rank 10 → 1/(10+60) = 0.0143
     * Prevents a single top result from dominating the merged list.
     */
    private static final int RRF_K = 60;

    private static final int CANDIDATE_SIZE = 20; // each search fetches this many

    private final VectorSearchRepository vectorSearch;
    private final BM25SearchRepository bm25Search;
    private final EmbeddingModel embeddingModel;

    public HybridSearchService(VectorSearchRepository vectorSearch,
                               BM25SearchRepository bm25Search,
                               EmbeddingModel embeddingModel) {
        this.vectorSearch = vectorSearch;
        this.bm25Search = bm25Search;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Runs hybrid search and returns RRF-merged candidates.
     *
     * @param question      the user's plain text question
     * @param versionFilter optional version label — null means search all versions
     * @return RRF-ranked list of deduplicated chunks, best first
     */
    public List<ScoredChunk> search(String question, String versionFilter) {
        // Step 1 — embed the question ONCE, share the vector with vector search
        // Same model as ingestion (text-embedding-3-small) so vectors are comparable
        float[] queryVector = embeddingModel.embed(question);
        log.debug("Question embedded — vector length={}", queryVector.length);

        // Step 2 — run both searches in parallel conceptually, sequentially in code
        List<ScoredChunk> vectorResults = vectorSearch.search(queryVector, versionFilter, CANDIDATE_SIZE);
        List<ScoredChunk> bm25Results   = bm25Search.search(question, versionFilter, CANDIDATE_SIZE);

        log.debug("Vector search: {} results, BM25 search: {} results",
                vectorResults.size(), bm25Results.size());

        // Step 3 — merge via RRF
        List<ScoredChunk> merged = reciprocalRankFusion(vectorResults, bm25Results);

        log.info("Hybrid search — vector={}, bm25={}, merged={}",
                vectorResults.size(), bm25Results.size(), merged.size());

        return merged;
    }

    // -----------------------------------------------------------------------
    // private — RRF implementation
    // -----------------------------------------------------------------------

    /**
     * Merges two ranked lists using Reciprocal Rank Fusion.
     *
     * Algorithm:
     *   For each chunk in each list, compute: 1 / (rank + RRF_K)
     *   If the same chunk appears in both lists, sum the contributions.
     *   Sort by combined RRF score descending.
     *
     * Chunks are identified by contentHash — same content = same chunk,
     * regardless of which search found it.
     *
     * RRF score formula:
     *   score = 1/(rank_in_vector + k) + 1/(rank_in_bm25 + k)
     *
     * If a chunk only appears in one list, the missing term is 0.
     */
    private List<ScoredChunk> reciprocalRankFusion(List<ScoredChunk> vectorResults,
                                                    List<ScoredChunk> bm25Results) {
        // LinkedHashMap preserves insertion order — useful for debugging
        // Key: contentHash — unique identifier for a chunk
        // Value: accumulated RRF score
        Map<String, Double> rrfScores = new LinkedHashMap<>();

        // Key: contentHash → the ScoredChunk (we need the chunk object, not just the score)
        Map<String, ScoredChunk> chunkByHash = new LinkedHashMap<>();

        // Process vector results — rank is 1-based (rank 1 = best)
        for (int rank = 1; rank <= vectorResults.size(); rank++) {
            ScoredChunk sc = vectorResults.get(rank - 1);
            String hash = sc.contentHash();
            double contribution = 1.0 / (rank + RRF_K);

            rrfScores.merge(hash, contribution, Double::sum);
            chunkByHash.putIfAbsent(hash, sc);
        }

        // Process BM25 results — add to existing score if chunk already seen
        for (int rank = 1; rank <= bm25Results.size(); rank++) {
            ScoredChunk sc = bm25Results.get(rank - 1);
            String hash = sc.contentHash();
            double contribution = 1.0 / (rank + RRF_K);

            rrfScores.merge(hash, contribution, Double::sum);
            chunkByHash.putIfAbsent(hash, sc);
        }

        // Build final list sorted by RRF score descending
        List<ScoredChunk> merged = new ArrayList<>();
        rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(entry -> {
                    ScoredChunk original = chunkByHash.get(entry.getKey());
                    // Replace the original score with the RRF score
                    merged.add(new ScoredChunk(original.chunk(), entry.getValue()));
                });

        return merged;
    }
}
