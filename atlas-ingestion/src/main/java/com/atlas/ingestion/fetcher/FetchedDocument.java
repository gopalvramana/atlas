package com.atlas.ingestion.fetcher;

/**
 * Value object representing a single .adoc file fetched from GitHub.
 *
 * Carries only what GitHubDocsFetcher knows:
 *   - filename  : e.g. "chat-client.adoc"
 *   - url       : GitHub html_url — stable identifier used in the chunks table
 *   - rawContent: raw .adoc text, passed to AsciiDocAdapter for extraction
 *
 * Version and branch are not stored here — they belong to IngestionService
 * which orchestrates the full pipeline.
 */
public record FetchedDocument(
        String filename,
        String url,
        String rawContent
) {}
