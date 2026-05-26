package com.atlas.retrieval.service;

import com.atlas.retrieval.config.RetrievalConfig;
import com.atlas.retrieval.model.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Reranks candidate chunks using the Cohere Rerank API (cross-encoder).
 *
 * Single responsibility: given a question and a list of candidate chunks,
 * return the top-N most relevant chunks scored by a cross-encoder.
 *
 * Why a cross-encoder is more accurate than cosine similarity:
 *   Cosine similarity: embed(question) vs embed(chunk) independently → approximate
 *   Cross-encoder:     model sees question + chunk together → accurate relevance score
 *
 * Called after HybridSearchService — receives up to 40 RRF-ranked candidates,
 * returns the best 8 for the LLM prompt.
 */
@Service
public class RerankingService {

    private static final Logger log = LoggerFactory.getLogger(RerankingService.class);

    private static final String COHERE_RERANK_URL = "https://api.cohere.com/v2/rerank";

    private final RestClient restClient;
    private final RetrievalConfig config;

    public RerankingService(@Value("${COHERE_API_KEY}") String cohereApiKey,
                            RetrievalConfig config) {
        this.config = config;
        this.restClient = RestClient.builder()
                .baseUrl(COHERE_RERANK_URL)
                .defaultHeader("Authorization", "Bearer " + cohereApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * Reranks candidates and returns the top-N most relevant chunks.
     *
     * @param question   the user's plain text question
     * @param candidates RRF-merged candidates from HybridSearchService (up to 40)
     * @return top-N chunks reranked by cross-encoder relevance score, best first
     */
    public List<ScoredChunk> rerank(String question, List<ScoredChunk> candidates) {
        if (candidates.isEmpty()) {
            log.warn("Reranker called with empty candidate list — returning empty");
            return List.of();
        }

        // Step 1 — extract plain text from each chunk
        // Cohere only accepts strings — not our ScoredChunk objects
        // Order matters — Cohere returns index positions that map back to this list
        List<String> documents = candidates.stream()
                .map(sc -> sc.chunk().getContent())
                .toList();

        log.debug("Reranking {} candidates for question: {}", documents.size(), question);

        // Step 2 — call Cohere Rerank API
        RerankRequest request = new RerankRequest(
                config.getRerankModel(),
                question,
                documents,
                config.getRerankTopN()
        );

        RerankResponse response = restClient.post()
                .body(request)
                .retrieve()
                .body(RerankResponse.class);

        if (response == null || response.results() == null) {
            log.error("Cohere rerank returned null response — falling back to RRF order");
            return candidates.subList(0, Math.min(config.getRerankTopN(), candidates.size()));
        }

        // Step 3 — map Cohere's index back to original ScoredChunk
        // Cohere returns: { index: 3, relevance_score: 0.94 }
        // index 3 → candidates.get(3) → the chunk that scored 0.94
        List<ScoredChunk> reranked = response.results().stream()
                .map(result -> new ScoredChunk(
                        candidates.get(result.index()).chunk(),
                        result.relevanceScore()
                ))
                .toList();

        log.info("Reranking complete — {} candidates → {} reranked chunks",
                candidates.size(), reranked.size());

        return reranked;
    }

    // -----------------------------------------------------------------------
    // Cohere API request / response records
    // -----------------------------------------------------------------------

    /**
     * Request body sent to Cohere Rerank API.
     * Field names use camelCase — Jackson serialises to JSON automatically.
     */
    private record RerankRequest(
            String model,
            String query,
            List<String> documents,
            int topN
    ) {}

    /**
     * Response from Cohere Rerank API.
     * results: list of { index, relevance_score } — ordered best first.
     */
    private record RerankResponse(List<RerankResult> results) {}

    /**
     * One result entry from Cohere.
     * index         → position in the documents list we sent
     * relevanceScore → cross-encoder confidence (0.0 to 1.0, higher = more relevant)
     */
    private record RerankResult(int index, double relevanceScore) {}
}
