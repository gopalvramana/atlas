package com.atlas.ingestion.repository;

import com.atlas.core.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Low-level JDBC writer for bulk chunk inserts into pgvector.
 *
 * Single responsibility: write List<Chunk> to the chunks table using
 * ON CONFLICT (content_hash) DO NOTHING for idempotent inserts.
 *
 * Spring Data JPA cannot express the pgvector cast (::vector) needed
 * for the embedding column — JDBC template handles it directly.
 */
@Component
public class ChunkJdbcWriter {

    private static final Logger log = LoggerFactory.getLogger(ChunkJdbcWriter.class);

    private static final String INSERT_SQL = """
            INSERT INTO chunks
                (id, source, version, section, url, content, content_hash, document_hash, embedding, ingested_at)
            VALUES
                (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?)
            ON CONFLICT (content_hash, version) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public ChunkJdbcWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts all chunks in a single batch.
     *
     * @return number of rows actually inserted (skipped duplicates not counted)
     */
    @Transactional
    public int insertAll(List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            return 0;
        }

        List<Object[]> batchArgs = chunks.stream()
                .map(this::toArgs)
                .toList();

        int[] results = jdbc.batchUpdate(INSERT_SQL, batchArgs);
        int inserted = Arrays.stream(results).sum();

        log.debug("Batch insert: {} submitted, {} inserted (rest were duplicates)",
                chunks.size(), inserted);
        return inserted;
    }

    // -----------------------------------------------------------------------
    // private helpers
    // -----------------------------------------------------------------------

    private Object[] toArgs(Chunk chunk) {
        return new Object[]{
                chunk.getId().toString(),                         // ?::uuid
                chunk.getSource().getLabel(),                     // source TEXT
                chunk.getVersion().getLabel(),                    // version TEXT
                chunk.getSection(),                               // section TEXT
                chunk.getUrl(),                                   // url TEXT
                chunk.getContent(),                               // content TEXT
                chunk.getContentHash(),                           // content_hash TEXT
                chunk.getDocumentHash(),                          // document_hash TEXT
                toVectorLiteral(chunk.getEmbedding()),            // ?::vector
                Timestamp.from(chunk.getIngestedAt() != null       // ingested_at TIMESTAMPTZ
                        ? chunk.getIngestedAt() : Instant.now())
        };
    }

    /**
     * Converts float[] to the pgvector literal format: "[0.1,0.2,...,0.n]"
     */
    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) sb.append(',');
        }
        sb.append(']');
        return sb.toString();
    }
}
