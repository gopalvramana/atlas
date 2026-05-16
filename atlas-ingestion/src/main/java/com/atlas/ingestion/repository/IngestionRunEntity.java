package com.atlas.ingestion.repository;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping the ingestion_runs table.
 *
 * Single responsibility: DB representation of one ingestion run.
 * Written once per version per run — used for observability and audit.
 */
@Entity
@Table(name = "ingestion_runs")
public class IngestionRunEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "run_at", nullable = false)
    private Instant runAt;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "branch", nullable = false)
    private String branch;

    @Column(name = "files_fetched")
    private Integer filesFetched;

    @Column(name = "chunks_produced")
    private Integer chunksProduced;

    @Column(name = "chunks_inserted")
    private Integer chunksInserted;

    @Column(name = "chunks_skipped")
    private Integer chunksSkipped;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "status", nullable = false)
    private String status;   // "SUCCESS" | "FAILED"

    @Column(name = "error_message")
    private String errorMessage;

    public IngestionRunEntity() {}

    // --- getters and setters ---

    public UUID getId()                            { return id; }
    public void setId(UUID id)                     { this.id = id; }

    public Instant getRunAt()                      { return runAt; }
    public void setRunAt(Instant runAt)            { this.runAt = runAt; }

    public String getSource()                      { return source; }
    public void setSource(String source)           { this.source = source; }

    public String getVersion()                     { return version; }
    public void setVersion(String version)         { this.version = version; }

    public String getBranch()                      { return branch; }
    public void setBranch(String branch)           { this.branch = branch; }

    public Integer getFilesFetched()               { return filesFetched; }
    public void setFilesFetched(Integer n)         { this.filesFetched = n; }

    public Integer getChunksProduced()             { return chunksProduced; }
    public void setChunksProduced(Integer n)       { this.chunksProduced = n; }

    public Integer getChunksInserted()             { return chunksInserted; }
    public void setChunksInserted(Integer n)       { this.chunksInserted = n; }

    public Integer getChunksSkipped()              { return chunksSkipped; }
    public void setChunksSkipped(Integer n)        { this.chunksSkipped = n; }

    public Long getDurationMs()                    { return durationMs; }
    public void setDurationMs(Long ms)             { this.durationMs = ms; }

    public String getStatus()                      { return status; }
    public void setStatus(String status)           { this.status = status; }

    public String getErrorMessage()                { return errorMessage; }
    public void setErrorMessage(String msg)        { this.errorMessage = msg; }
}
