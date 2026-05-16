package com.atlas.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds atlas.ingestion.* (excluding github) from application.yml.
 *
 * Single responsibility: typed access to chunk and batch size configuration.
 */
@Component
@ConfigurationProperties(prefix = "atlas.ingestion")
public class IngestionConfig {

    private int chunkSize = 512;
    private int chunkOverlap = 64;
    private int batchSize = 100;

    public int getChunkSize()                  { return chunkSize; }
    public void setChunkSize(int chunkSize)    { this.chunkSize = chunkSize; }

    public int getChunkOverlap()               { return chunkOverlap; }
    public void setChunkOverlap(int overlap)   { this.chunkOverlap = overlap; }

    public int getBatchSize()                  { return batchSize; }
    public void setBatchSize(int batchSize)    { this.batchSize = batchSize; }
}
