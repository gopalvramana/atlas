package com.atlas.ingestion.repository;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping the chunks table.
 *
 * Single responsibility: DB representation of a chunk.
 * Domain model (Chunk.java) is separate — ChunkEntity is persistence-only.
 */
@Entity
@Table(name = "chunks")
public class ChunkEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "section")
    private String section;

    @Column(name = "url")
    private String url;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_hash", nullable = false, unique = true)
    private String contentHash;

    @Column(name = "document_hash")
    private String documentHash;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "embedding", columnDefinition = "vector(1536)")
    private float[] embedding;

    @Column(name = "ingested_at")
    private Instant ingestedAt;

    public ChunkEntity() {}

    // --- getters and setters ---

    public UUID getId()                          { return id; }
    public void setId(UUID id)                   { this.id = id; }

    public String getSource()                    { return source; }
    public void setSource(String source)         { this.source = source; }

    public String getVersion()                   { return version; }
    public void setVersion(String version)       { this.version = version; }

    public String getSection()                   { return section; }
    public void setSection(String section)       { this.section = section; }

    public String getUrl()                       { return url; }
    public void setUrl(String url)               { this.url = url; }

    public String getContent()                   { return content; }
    public void setContent(String content)       { this.content = content; }

    public String getContentHash()               { return contentHash; }
    public void setContentHash(String hash)      { this.contentHash = hash; }

    public String getDocumentHash()              { return documentHash; }
    public void setDocumentHash(String hash)     { this.documentHash = hash; }

    public float[] getEmbedding()                { return embedding; }
    public void setEmbedding(float[] embedding)  { this.embedding = embedding; }

    public Instant getIngestedAt()               { return ingestedAt; }
    public void setIngestedAt(Instant t)         { this.ingestedAt = t; }
}
