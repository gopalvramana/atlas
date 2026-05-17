# Atlas — Build Progress

Updated at every commit checkpoint.
Read this at the start of every session to know exactly where we are.

---

## Current Status — 2026-05-16

### ✅ Done

#### Infrastructure
- PostgreSQL + pgvector running (`roms-postgres` Docker container)
- `atlas` database created, `roms_user` owner
- Flyway schema versioning set up

#### Schema (Flyway migrations)
- `V1__initial_schema.sql` — `chunks` table with ivfflat + GIN + version indexes, `eval_runs` table
- `V2__ingestion_runs.sql` — `ingestion_runs` table
- `V3__add_document_hash.sql` — `document_hash` column + indexes on `chunks`
- `V4__scope_content_hash_to_version.sql` — unique constraint changed from `(content_hash)` to `(content_hash, version)`

#### atlas-core (domain model)
- `Version.java` — enum (1.0-GA, 1.1, 2.0-M) with `fromLabel()`
- `ChunkSource.java` — enum (SPRING_AI_DOCS, SPRING_AI_GITHUB)
- `Chunk.java` — domain object with all fields including `documentHash`
- `AtlasQuery.java` — question + nullable version, `hasVersionFilter()`
- `Citation.java` — section, url, excerpt
- `AtlasResponse.java` — answer, citations, availableVersions

#### atlas-ingestion (complete ✅)
- `application.yml` — full config wired (chunk-size, overlap, batch-size, GitHub versions/paths)
- `DocumentAdapter.java` — interface (supports + extractText)
- `AsciiDocAdapter.java` — .adoc → AsciidoctorJ → Jsoup → plain text
- `FetchedDocument.java` — value object (filename, url, rawContent)
- `GitHubConfig.java` — `@ConfigurationProperties` for `atlas.ingestion.github.*`
- `GitHubDocsFetcher.java` — fetch raw .adoc from GitHub Contents API + Raw URL
- `GitHubFetchException.java` — unchecked exception for non-recoverable GitHub failures
- `IngestionConfig.java` — `@ConfigurationProperties` for chunk-size, overlap, batch-size
- `ChunkingService.java` — jtokkit cl100k_base, sliding window 512/64, SHA-256 hashes
- `EmbeddingService.java` — OpenAI text-embedding-3-small, batch 100, exponential backoff retry
- `EmbeddingException.java` — unchecked exception after retries exhausted
- `DotenvEnvironmentPostProcessor.java` — loads `.env` before Spring autoconfiguration
- `ChunkEntity.java` — JPA entity for `chunks` table with pgvector `float[]`
- `ChunkRepository.java` — Spring Data JPA: `findDocumentHash`, `deleteByUrlAndVersion`
- `ChunkJdbcWriter.java` — raw JDBC batch insert, `ON CONFLICT (content_hash, version) DO NOTHING`
- `IngestionRunEntity.java` — JPA entity for `ingestion_runs` table
- `IngestionRunRepository.java` — Spring Data JPA for audit rows
- `IngestionService.java` — full orchestrator: fetch → extract → chunk → embed → store → record
- `IngestionCli.java` — entry point, delegates entirely to `IngestionService.ingestAll()`

#### Documentation
- `docs/ingestion-pipeline.md` — Mermaid flowchart + step-by-step explanation (up to date)
- `docs/guidelines.md` — full engineering guidelines
- `docs/decisions.md` — ADR log
- `CLAUDE.md` — Atlas-specific Claude Code instructions
- `~/.claude/CLAUDE.md` — global universal guidelines (Java best practices, SOLID, etc.)

#### Verified DB state (post ingestion)
| Version | Branch | Files | Chunks |
|---|---|---|---|
| 1.0-GA | 1.0.x | 29 | 167 |
| 1.1 | 1.1.x | 33 | 192 |
| 2.0-M | main | 33 | 211 |
| **Total** | | **95** | **570** |

---

### ⬜ Next — atlas-query

The query module: REST API that accepts a question, embeds it, and does vector similarity
search against the `chunks` table to return ranked, version-filtered results.

| Class | Responsibility |
|---|---|
| `QueryController` | REST endpoint — POST /query, returns AtlasResponse |
| `QueryService` | Orchestrate embed question → search → assemble response |
| `VectorSearchRepository` | pgvector similarity search with version filter |
| `RerankingService` | Optional — RRF or cross-encoder reranking of results |

---

### ⬜ Pending (future steps)

- `atlas-agent` — ReAct agent loop with tool calling
- `atlas-mcp` — stdio MCP server
- `atlas-evals` — evaluation suite
