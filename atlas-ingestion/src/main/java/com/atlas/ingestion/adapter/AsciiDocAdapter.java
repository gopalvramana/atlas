package com.atlas.ingestion.adapter;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Extracts clean plain text from AsciiDoc (.adoc) files.
 *
 * Flow:
 *   raw .adoc content
 *       → AsciidoctorJ converts to HTML
 *       → Jsoup extracts plain text from HTML
 *       → image alt text preserved inline
 *       → clean plain text returned
 */
@Component
public class AsciiDocAdapter implements DocumentAdapter {

    private static final Logger log = LoggerFactory.getLogger(AsciiDocAdapter.class);

    private final Asciidoctor asciidoctor = Asciidoctor.Factory.create();

    @Override
    public boolean supports(String fileExtension) {
        return ".adoc".equalsIgnoreCase(fileExtension)
                || ".asciidoc".equalsIgnoreCase(fileExtension);
    }

    @Override
    public String extractText(String rawContent, String url) {
        try {
            // Step 1: convert .adoc to HTML using AsciidoctorJ
            Options options = Options.builder()
                    .safe(SafeMode.SAFE)
                    .standalone(false)   // body content only — no <html><head> wrapper
                    .build();

            String html = asciidoctor.convert(rawContent, options);

            // Step 2: parse HTML with Jsoup
            Document doc = Jsoup.parse(html);

            // Step 3: replace image tags with their alt text inline
            // so diagram descriptions are preserved in the extracted text
            Elements images = doc.select("img[alt]");
            for (Element img : images) {
                String altText = img.attr("alt");
                if (!altText.isBlank()) {
                    img.replaceWith(new org.jsoup.nodes.TextNode("[Image: " + altText + "]"));
                } else {
                    img.remove();
                }
            }

            // Step 4: extract plain text — Jsoup handles tag stripping
            String text = doc.body().text();

            // Step 5: normalise whitespace
            text = text.replaceAll("\\s{3,}", "\n\n").trim();

            return text;

        } catch (Exception e) {
            log.warn("Failed to extract text from AsciiDoc file: {} — {}", url, e.getMessage());
            return "";
        }
    }
}
