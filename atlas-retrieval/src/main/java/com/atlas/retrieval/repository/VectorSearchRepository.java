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
 * Searches the chunks table using pgvector cosine similarity.
 *
 * Single responsibility: given a query vector, return the top-N most
 * semantically similar chunks from the DB.
 *
 * Does NOT embed the question — the caller must supply a pre-computed
 * float[] vector. This keeps embedding a single shared concern in
 * HybridSearchService, avoiding double embedding.
 */
@Repository
public class VectorSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchRepository.class);

    /**
     * SQL explanation:
     *
     * 1 - (embedding <=> ?::vector) AS score
     *   <=>  : pgvector cosine DISTANCE operator (0.0 = identical, 2.0 = opposite)
     *   1 -  : converts distance to similarity score (1.0 = identical, -1.0 = opposite)
     *
     * The vector is passed twice (?):
     *   First  ? → score calculation  (1 - distance)
     *   Second ? → ORDER BY sorting
     * Both must be the same value — positional JDBC parameters require it.
     *
     * embedding column is intentionally excluded from SELECT —
     * 1536 floats per row is expensive to deserialise and unused after retrieval.
     */
    private static final String SEARCH_SQL = """
            SELECT id, source, version, section, url,
                   content, content_hash, document_hash, ingested_at,
                   1 - (embedding <=> ?::vector) AS score
            FROM chunks
            WHERE (? IS NULL OR version = ?)
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public VectorSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the top-K chunks most similar to the query vector.
     *
     * @param queryVector   1536-dim embedding of the user's question
     * @param versionFilter optional — restrict search to a specific version label
     *                      (e.g. "1.1"). Pass null to search all versions.
     * @param topK          maximum number of chunks to return
     * @return chunks ranked by cosine similarity, highest score first
     */
    public List<ScoredChunk> search(float[] queryVector, String versionFilter, int topK) {
        String vectorLiteral = toVectorLiteral(queryVector);
        log.debug("Vector search — version={}, topK={}", versionFilter, topK);

        List<ScoredChunk> results = jdbc.query(
                SEARCH_SQL,
                chunkRowMapper(),
                vectorLiteral,   // ? for score calculation
                versionFilter,   // ? for WHERE version filter (null = no filter)
                versionFilter,   // ? for version = ? comparison
                vectorLiteral,   // ? for ORDER BY
                topK             // ? for LIMIT
        );

        log.debug("Vector search returned {} chunks", results.size());
        return results;
    }

    // -----------------------------------------------------------------------
    // private helpers
    // -----------------------------------------------------------------------

    /**
     * Maps a ResultSet row to a ScoredChunk.
     * The score column is the computed cosine similarity (1 - cosine distance).
     */
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

    /**
     * Converts float[] to pgvector literal format: "[0.1,0.2,...,0.n]"
     * PostgreSQL requires this string format for the ::vector cast.
     */
    private String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(',');
            }
        }
        sb.append(']');
        return sb.toString();
    }
}
