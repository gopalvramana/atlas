package com.atlas.ingestion.embedding;

/**
 * Thrown when OpenAI embedding calls fail after all retries are exhausted.
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
