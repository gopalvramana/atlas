package com.atlas.retrieval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the retrieval pipeline.
 *
 * Single responsibility: hold all tunable retrieval parameters in one place.
 * Bound from application.yml under atlas.retrieval.*
 */
@ConfigurationProperties(prefix = "atlas.retrieval")
public class RetrievalConfig {

    /**
     * Number of candidates fetched by each search (vector and BM25).
     * Both searches use this as their topK.
     * After RRF merge, up to 2 × candidateSize chunks are available for reranking.
     */
    private int candidateSize = 20;

    /**
     * Number of chunks the reranker returns after scoring.
     * These are the final chunks passed to the LLM as context.
     */
    private int rerankTopN = 8;

    /**
     * Cohere rerank model to use.
     * rerank-english-v3.0 is the current production model.
     */
    private String rerankModel = "rerank-english-v3.0";

    public int getCandidateSize()              { return candidateSize; }
    public void setCandidateSize(int n)        { this.candidateSize = n; }

    public int getRerankTopN()                 { return rerankTopN; }
    public void setRerankTopN(int n)           { this.rerankTopN = n; }

    public String getRerankModel()             { return rerankModel; }
    public void setRerankModel(String model)   { this.rerankModel = model; }
}
