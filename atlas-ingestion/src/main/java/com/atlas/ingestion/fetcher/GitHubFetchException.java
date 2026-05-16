package com.atlas.ingestion.fetcher;

/**
 * Thrown when GitHub API calls fail with a non-retryable or unrecoverable error.
 * Retry logic is handled by IngestionService — this exception signals that
 * GitHubDocsFetcher cannot recover on its own.
 */
public class GitHubFetchException extends RuntimeException {

    public GitHubFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
