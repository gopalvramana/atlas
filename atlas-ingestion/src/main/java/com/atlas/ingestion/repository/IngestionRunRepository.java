package com.atlas.ingestion.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data JPA repository for ingestion run records.
 *
 * Single responsibility: persist and retrieve ingestion_runs rows.
 * IngestionService calls save() once per version after each run completes.
 */
public interface IngestionRunRepository extends JpaRepository<IngestionRunEntity, UUID> {
}
