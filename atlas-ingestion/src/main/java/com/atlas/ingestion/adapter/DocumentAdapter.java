package com.atlas.ingestion.adapter;

/**
 * Contract for all document format adapters.
 *
 * Each adapter knows how to:
 *  1. Identify whether it can handle a given file extension
 *  2. Extract clean plain text from raw file content
 *
 * The rest of the pipeline (chunking → embedding → storing) is
 * format-agnostic and works on the plain text output of this interface.
 *
 * To support a new format, implement this interface and annotate
 * the class with @Component — the pipeline picks it up automatically.
 */
public interface DocumentAdapter {

    /**
     * Returns true if this adapter can handle the given file extension.
     * Example: ".adoc", ".md", ".html"
     */
    boolean supports(String fileExtension);

    /**
     * Extracts clean plain text from raw file content.
     * Strips format-specific markup (headings, macros, tags etc).
     * Captures image alt text and captions as inline text.
     *
     * @param rawContent  raw file content as a String
     * @param url         source URL — used for context in logging
     * @return clean plain text ready for chunking
     */
    String extractText(String rawContent, String url);
}
