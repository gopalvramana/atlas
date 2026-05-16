package com.atlas;

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
    private final GitHubConfig config;

    public IngestionCli(GitHubDocsFetcher fetcher, GitHubConfig config) {
        this.fetcher = fetcher;
        this.config = config;
    }

    public static void main(String[] args) {
        loadDotenv();
        SpringApplication.run(IngestionCli.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== Atlas Ingestion — Fetch Test ===");

        for (GitHubConfig.VersionConfig version : config.getVersions()) {
            log.info("Fetching version: {} (branch: {})", version.getLabel(), version.getBranch());

            List<FetchedDocument> documents = fetcher.fetch(version.getBranch());

            log.info("Version: {} — {} files fetched", version.getLabel(), documents.size());
            documents.forEach(doc ->
                log.info("  ✓ {} — {}", doc.filename(), doc.url())
            );
        }

        log.info("=== Fetch complete ===");
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
