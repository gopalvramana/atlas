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

#### atlas-core (domain model)
- `Version.java` — enum (0.8, 1.0-GA, 1.1+) with `fromLabel()`
- `ChunkSource.java` — enum (SPRING_AI_DOCS, SPRING_AI_GITHUB)
- `Chunk.java` — domain object with all fields including `documentHash`
- `AtlasQuery.java` — question + nullable version, `hasVersionFilter()`
- `Citation.java` — section, url, excerpt
- `AtlasResponse.java` — answer, citations, availableVersions

#### atlas-ingestion
- `application.yml` — full config wired (chunk-size, overlap, batch-size, GitHub versions/paths)
- `DocumentAdapter.java` — interface (supports + extractText)
- `AsciiDocAdapter.java` — .adoc → AsciidoctorJ → Jsoup → plain text

#### Documentation
- `docs/ingestion-pipeline.md` — Mermaid flowchart + step-by-step explanation
- `docs/guidelines.md` — full engineering guidelines
- `docs/decisions.md` — ADR log (this session)
- `CLAUDE.md` — Atlas-specific Claude Code instructions
- `~/.claude/CLAUDE.md` — global universal guidelines (Java best practices, SOLID, etc.)

---

### ⬜ Next — atlas-ingestion (remaining classes)

**Plan before code for each:**

| Class | Responsibility |
|---|---|
| `FetchedDocument` | Value object — filename, url, rawContent |
| `GitHubConfig` | `@ConfigurationProperties` binding for `atlas.ingestion.github.*` |
| `GitHubDocsFetcher` | Fetch raw .adoc files from GitHub API for each version |
| `ChunkingService` | Split plain text → overlapping token windows + hashes |
| `EmbeddingService` | Batch chunks → OpenAI embeddings with retry |
| `ChunkEntity` | JPA entity for `chunks` table |
| `IngestionRunEntity` | JPA entity for `ingestion_runs` table |
| `ChunkRepository` | Spring Data JPA — persist + document_hash lookup + delete by url+version |
| `IngestionRunRepository` | Spring Data JPA — write ingestion_runs row |
| `IngestionService` | Orchestrate fetch → extract → chunk → embed → store → record |
| `IngestionCli` | CLI entry point — calls IngestionService per version |

---

### ⬜ Pending (future steps)

- `atlas-retrieval` — hybrid search (BM25 + pgvector + RRF)
- `atlas-agent` — ReAct agent loop with tool calling
- `atlas-api` — REST + SSE endpoints
- `atlas-mcp` — stdio MCP server
- `atlas-evals` — evaluation suite
