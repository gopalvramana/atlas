# Atlas — Project Guidelines

Extends `~/.claude/CLAUDE.md`. All universal rules apply here too.

Full engineering guidelines: [`docs/guidelines.md`](docs/guidelines.md)

---

## Session Start — Read These First

Before touching any code at the start of a session:
1. Read [`docs/progress.md`](docs/progress.md) — know exactly where we are
2. Read [`docs/decisions.md`](docs/decisions.md) — recover all prior decisions

## Commit Rule — Always Update These

At every commit checkpoint, update in the same commit:
- [`docs/progress.md`](docs/progress.md) — mark completed items ✅, update next steps
- [`docs/decisions.md`](docs/decisions.md) — append any new ADRs made since last commit

Mid-session critical decisions → use `/remember` immediately.

---

## Technology Stack

| Concern | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.4.x, Spring AI 1.1.5 |
| Embeddings | OpenAI `text-embedding-3-small` (1536 dimensions) |
| LLM | Anthropic Claude — Haiku for tool steps, Sonnet for final answer |
| Token counting | jtokkit `cl100k_base` — same encoding as text-embedding-3-small |
| AsciiDoc parsing | AsciidoctorJ 3.0.1 → Jsoup 1.22.2 (.adoc → HTML → plain text) |
| Database | PostgreSQL + pgvector (`roms-postgres` Docker container) |
| Schema versioning | Flyway — never modify existing migrations, always add new `V{n}__description.sql` |
| LangChain4j | Deferred to v2 — not in scope |

---

## Module Responsibilities

| Module | Single responsibility |
|---|---|
| `atlas-core` | Shared domain model only — `Chunk`, `Version`, `ChunkSource`, `AtlasQuery`, `Citation`, `AtlasResponse` |
| `atlas-ingestion` | CLI pipeline — fetch → extract → chunk → embed → store |
| `atlas-retrieval` | Hybrid search — BM25 + pgvector + RRF |
| `atlas-agent` | ReAct agent loop with tool calling |
| `atlas-api` | REST + SSE endpoints |
| `atlas-mcp` | stdio MCP server |
| `atlas-evals` | Evaluation suite — evals as CI gate |

---

## Idempotency Contract

The ingestion pipeline must be safely re-runnable at any time:

- **Document level:** `document_hash` (SHA-256 of full `.adoc` content)
  - Unchanged → skip entire file
  - Changed → DELETE old chunks for `url + version`, reprocess
- **Chunk level:** `ON CONFLICT (content_hash) DO NOTHING`
  - Silent DB guard against concurrent duplicate inserts — not a flow decision

---

## Flyway Rules

- Migration files live in `src/main/resources/db/migration/` in **both** `atlas-ingestion` and `atlas-api`.
- Never edit an already-applied migration — always create a new versioned file.
- After creating a new migration, apply it to the local DB via Docker and seed `flyway_schema_history`.

---

## Running Locally

```bash
# 1. Copy and fill in credentials
cp .env.example .env

# 2. Ensure roms-postgres container is running in Docker Desktop

# 3. Run ingestion CLI
mvn exec:java -pl atlas-ingestion

# 4. Run API
mvn spring-boot:run -pl atlas-api
```
