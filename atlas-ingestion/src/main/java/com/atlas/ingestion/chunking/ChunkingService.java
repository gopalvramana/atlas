package com.atlas.ingestion.chunking;

import com.atlas.core.Chunk;
import com.atlas.core.ChunkSource;
import com.atlas.core.Version;
import com.atlas.ingestion.config.IngestionConfig;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Splits plain text into overlapping token windows and returns a list of Chunk objects.
 *
 * Single responsibility: tokenise, slide, hash. No fetching, no embedding, no DB access.
 *
 * Uses jtokkit cl100k_base — same encoding as OpenAI text-embedding-3-small,
 * so token counts are accurate and chunk boundaries are consistent.
 */
@Component
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    private final IngestionConfig config;
    private final Encoding encoding;

    public ChunkingService(IngestionConfig config) {
        this.config = config;
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    /**
     * Splits plain text into overlapping token-window chunks.
     *
     * @param plainText    extracted plain text from AsciiDocAdapter
     * @param source       e.g. SPRING_AI_GITHUB
     * @param version      e.g. V_1_0_GA
     * @param section      derived from filename e.g. "chatclient"
     * @param url          GitHub html_url — stable identifier for this document
     * @param documentHash SHA-256 of the full raw .adoc content (computed by IngestionService)
     * @return list of chunks — no embedding yet
     */
    public List<Chunk> chunk(String plainText, ChunkSource source, Version version,
                             String section, String url, String documentHash) {

        if (plainText == null || plainText.isBlank()) {
            log.warn("Empty plain text — skipping chunking for: {}", url);
            return List.of();
        }

        int chunkSize    = config.getChunkSize();
        int chunkOverlap = config.getChunkOverlap();
        int stepSize     = chunkSize - chunkOverlap;

        // Encode full document text to token IDs
        IntArrayList tokenIds = encoding.encode(plainText);
        int totalTokens = tokenIds.size();

        if (totalTokens == 0) {
            log.warn("Zero tokens after encoding — skipping: {}", url);
            return List.of();
        }

        List<Chunk> chunks = new ArrayList<>();
        int start = 0;

        while (start < totalTokens) {
            int end = Math.min(start + chunkSize, totalTokens);

            // Decode this window back to text
            IntArrayList windowTokens = slice(tokenIds, start, end);
            String chunkText = encoding.decode(windowTokens);

            String contentHash = sha256(chunkText);

            Chunk chunk = new Chunk(
                    UUID.randomUUID(),
                    source,
                    version,
                    section,
                    url,
                    chunkText,
                    contentHash,
                    documentHash,
                    null,   // embedding — populated by EmbeddingService
                    null    // ingestedAt — populated by ChunkRepository
            );

            chunks.add(chunk);

            if (end == totalTokens) break;
            start += stepSize;
        }

        log.debug("Chunked {} — {} tokens → {} chunks", section, totalTokens, chunks.size());
        return chunks;
    }

    // --- private helpers ---

    private IntArrayList slice(IntArrayList source, int from, int to) {
        IntArrayList result = new IntArrayList(to - from);
        for (int i = from; i < to; i++) {
            result.add(source.get(i));
        }
        return result;
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
