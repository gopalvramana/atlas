package com.atlas.retrieval.repository;

import com.atlas.core.Chunk;
import com.atlas.core.ChunkSource;
import com.atlas.core.Version;
import com.atlas.retrieval.model.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Searches the chunks table using PostgreSQL full-text search (BM25 approximation).
 *
 * Single responsibility: given a plain text question, return the top-N chunks
 * that best match by keyword relevance.
 *
 * Complements VectorSearchRepository:
 *   VectorSearchRepository  → finds chunks by meaning  (handles vocabulary mismatch)
 *   BM25SearchRepository    → finds chunks by keywords (handles exact term lookup)
 *
 * Both results are merged by HybridSearchService using Reciprocal Rank Fusion.
 */
@Repository
public class BM25SearchRepository {

    private static final Logger log = LoggerFactory.getLogger(BM25SearchRepository.class);

    /**
     * SQL explanation:
     *
     * plainto_tsquery('english', ?)
     *   Converts the plain question string into a tsquery.
     *   Applies the same normalisation pipeline as content_tsv:
     *   tokenise → remove stop words → stem → lowercase.
     *   "How does ChatClient work" → 'chatclient' & 'work'
     *
     * content_tsv @@ plainto_tsquery(...)
     *   @@ is the match operator.
     *   Filters to only chunks where the normalised content contains the search terms.
     *   Chunks that don't match are excluded before ranking.
     *
     * ts_rank(content_tsv, plainto_tsquery(...))
     *   Scores how well each chunk matches the query.
     *   Approximates BM25 — considers term frequency and position.
     *   Higher score = better keyword match.
     *
     * ? appears 5 times — JDBC positional parameters, passed in order:
     *   #1 question  → ts_rank scoring
     *   #2 question  → @@ filter
     *   #3 versionFilter → IS NULL check
     *   #4 versionFilter → version = ? comparison
     *   #5 topK      → LIMIT
     */
    private static final String SEARCH_SQL = """
            SELECT id, source, version, section, url,
                   content, content_hash, document_hash, ingested_at,
                   ts_rank(content_tsv, plainto_tsquery('english', ?)) AS score
            FROM chunks
            WHERE content_tsv @@ plainto_tsquery('english', ?)
            AND (? IS NULL OR version = ?)
            ORDER BY score DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public BM25SearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the top-K chunks most relevant to the question by keyword match.
     *
     * @param question      the user's plain text question
     * @param versionFilter optional — restrict search to a specific version label
     *                      (e.g. "1.1"). Pass null to search all versions.
     * @param topK          maximum number of chunks to return
     * @return chunks ranked by ts_rank score, highest score first
     */
    public List<ScoredChunk> search(String question, String versionFilter, int topK) {
        log.debug("BM25 search — version={}, topK={}", versionFilter, topK);

        List<ScoredChunk> results = jdbc.query(
                SEARCH_SQL,
                chunkRowMapper(),
                question,       // #1 — ts_rank scoring
                question,       // #2 — @@ filter
                versionFilter,  // #3 — IS NULL check
                versionFilter,  // #4 — version = ? comparison
                topK            // #5 — LIMIT
        );

        log.debug("BM25 search returned {} chunks", results.size());
        return results;
    }

    // -----------------------------------------------------------------------
    // private helpers
    // -----------------------------------------------------------------------

    private RowMapper<ScoredChunk> chunkRowMapper() {
        return (rs, rowNum) -> {
            Chunk chunk = new Chunk();
            chunk.setId(rs.getObject("id", java.util.UUID.class));
            chunk.setSource(ChunkSource.fromLabel(rs.getString("source")));
            chunk.setVersion(Version.fromLabel(rs.getString("version")));
            chunk.setSection(rs.getString("section"));
            chunk.setUrl(rs.getString("url"));
            chunk.setContent(rs.getString("content"));
            chunk.setContentHash(rs.getString("content_hash"));
            chunk.setDocumentHash(rs.getString("document_hash"));

            java.sql.Timestamp ts = rs.getTimestamp("ingested_at");
            chunk.setIngestedAt(ts != null ? ts.toInstant() : null);

            double score = rs.getDouble("score");
            return new ScoredChunk(chunk, score);
        };
    }
}
