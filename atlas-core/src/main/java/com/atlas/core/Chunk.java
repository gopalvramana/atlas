package com.atlas.core;

import java.time.Instant;
import java.util.UUID;

public class Chunk {

    private UUID id;
    private ChunkSource source;   // where this chunk came from
    private Version version;      // which Spring AI version this chunk belongs to
    private String section;       // doc section e.g. "ChatClient", "EmbeddingModel"
    private String url;           // original URL — used as citation link in responses
    private String content;       // the actual text of the chunk
    private String contentHash;   // SHA-256 of chunk text — chunk-level duplicate guard
    private String documentHash;  // SHA-256 of full source document — document-level idempotency
    private float[] embedding;    // vector representation — populated by EmbeddingService
    private Instant ingestedAt;   // populated by ChunkRepository on insert

    public Chunk() {}

    public Chunk(UUID id, ChunkSource source, Version version, String section,
                 String url, String content, String contentHash, String documentHash,
                 float[] embedding, Instant ingestedAt) {
        this.id = id;
        this.source = source;
        this.version = version;
        this.section = section;
        this.url = url;
        this.content = content;
        this.contentHash = contentHash;
        this.documentHash = documentHash;
        this.embedding = embedding;
        this.ingestedAt = ingestedAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public ChunkSource getSource() { return source; }
    public void setSource(ChunkSource source) { this.source = source; }

    public Version getVersion() { return version; }
    public void setVersion(Version version) { this.version = version; }

    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public String getDocumentHash() { return documentHash; }
    public void setDocumentHash(String documentHash) { this.documentHash = documentHash; }

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }

    public Instant getIngestedAt() { return ingestedAt; }
    public void setIngestedAt(Instant ingestedAt) { this.ingestedAt = ingestedAt; }
}
