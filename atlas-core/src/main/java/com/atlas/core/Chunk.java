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
    private String contentHash;   // SHA-256 of content — used for idempotent upsert
    private float[] embedding;    // vector representation — populated by EmbeddingService
    private Instant ingestedAt;

    public Chunk() {}

    public Chunk(ChunkSource source, Version version, String section,
                 String url, String content, String contentHash) {
        this.source = source;
        this.version = version;
        this.section = section;
        this.url = url;
        this.content = content;
        this.contentHash = contentHash;
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

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }

    public Instant getIngestedAt() { return ingestedAt; }
    public void setIngestedAt(Instant ingestedAt) { this.ingestedAt = ingestedAt; }
}
