package com.atlas;

import com.atlas.core.Chunk;
import com.atlas.core.ChunkSource;
import com.atlas.core.Version;
import com.atlas.ingestion.adapter.AsciiDocAdapter;
import com.atlas.ingestion.chunking.ChunkingService;
import com.atlas.ingestion.config.GitHubConfig;
import com.atlas.ingestion.fetcher.FetchedDocument;
import com.atlas.ingestion.fetcher.GitHubDocsFetcher;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

import java.util.List;

@SpringBootApplication
@ConfigurationPropertiesScan("com.atlas.ingestion.config")
@ComponentScan(basePackages = "com.atlas")
public class IngestionCli implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionCli.class);

    private final GitHubDocsFetcher fetcher;
    private final AsciiDocAdapter asciiDocAdapter;
    private final ChunkingService chunkingService;
    private final GitHubConfig config;

    public IngestionCli(GitHubDocsFetcher fetcher, AsciiDocAdapter asciiDocAdapter,
                        ChunkingService chunkingService, GitHubConfig config) {
        this.fetcher = fetcher;
        this.asciiDocAdapter = asciiDocAdapter;
        this.chunkingService = chunkingService;
        this.config = config;
    }

    public static void main(String[] args) {
        loadDotenv();
        SpringApplication.run(IngestionCli.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== Atlas Ingestion — Fetch + Extract + Chunk Test ===");

        for (GitHubConfig.VersionConfig versionConfig : config.getVersions()) {
            Version version = Version.fromLabel(versionConfig.getLabel());
            log.info("Processing version: {} (branch: {})", version.getLabel(), version.getBranch());

            List<FetchedDocument> documents = fetcher.fetch(version.getBranch());
            log.info("Fetched {} files", documents.size());

            int totalChunks = 0;

            for (FetchedDocument doc : documents) {
                // Step 2 — Extract: .adoc → plain text
                String plainText = asciiDocAdapter.extractText(doc.rawContent(), doc.url());

                // document hash — SHA-256 of raw .adoc (before extraction)
                String documentHash = ChunkingService.sha256(doc.rawContent());

                // section — filename without extension
                String section = doc.filename().replace(".adoc", "");

                // Step 3 — Chunk: plain text → List<Chunk>
                List<Chunk> chunks = chunkingService.chunk(
                        plainText, ChunkSource.SPRING_AI_GITHUB,
                        version, section, doc.url(), documentHash
                );

                totalChunks += chunks.size();
                log.info("  ✓ {} → {} chunks", doc.filename(), chunks.size());
            }

            log.info("Version: {} — {} files → {} total chunks", version.getLabel(), documents.size(), totalChunks);
        }

        log.info("=== Chunk test complete ===");
    }

    private static void loadDotenv() {
        try {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
            dotenv.entries().forEach(e -> {
                if (System.getenv(e.getKey()) == null) {
                    System.setProperty(e.getKey(), e.getValue());
                }
            });
        } catch (DotenvException ex) {
            // .env not present — rely on environment variables
        }
    }
}
