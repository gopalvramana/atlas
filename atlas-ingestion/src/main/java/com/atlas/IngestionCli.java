package com.atlas;

import com.atlas.ingestion.service.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point for the Atlas ingestion CLI.
 *
 * Single responsibility: boot Spring and delegate to IngestionService.
 * All pipeline logic lives in IngestionService and its collaborators.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.atlas.ingestion.config")
@ComponentScan(basePackages = "com.atlas")
public class IngestionCli implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionCli.class);

    private final IngestionService ingestionService;

    public IngestionCli(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    public static void main(String[] args) {
        SpringApplication.run(IngestionCli.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== Atlas Ingestion — starting ===");
        ingestionService.ingestAll();
        log.info("=== Atlas Ingestion — complete ===");
    }
}
