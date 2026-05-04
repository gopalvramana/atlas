# Atlas — Implementation Architecture

This document captures the full implementation design for Atlas. It is a working reference — decisions here will be revisited and updated as implementation proceeds. Open questions and pending decisions are marked explicitly.

---

## Repository structure

One standalone repo: `atlas` (separate from `engineering-portfolio`, which is documentation only).

```
atlas/
├── atlas-ingestion/          # CLI module: crawl, chunk, embed, load into PostgreSQL
├── atlas-core/               # Shared domain model: Chunk, Version, structured output DTOs
├── atlas-retrieval/          # Hybrid search: BM25 + PGVector + metadata filter + RRF fusion
├── atlas-agent/              # ReAct agent, tool registrations, prompt templates
├── atlas-api/                # Runnable Spring Boot app: REST + SSE endpoints
├── atlas-mcp/                # Runnable MCP server: stdio transport, exposes ask_atlas tool
├── atlas-evals/              # Eval dataset, eval runner, CI integration
├── infra/
│   ├── docker-compose.yml    # PostgreSQL + PGVector for local dev
│   └── schema.sql            # DDL: chunks table, eval_runs table
└── docs/
    └── adr/                  # Architecture Decision Records — one file per key decision
```

Multi-module Maven project. `atlas-api` and `atlas-mcp` are the two runnable Spring Boot applications. All other modules are libraries. `atlas-ingestion` runs as a CLI via `mvn exec:java`.

---

## Data model

Two tables. Everything in PostgreSQL — no separate vector database, no cache layer in v1.

### `chunks` — the ingested knowledge base

```sql
CREATE TABLE chunks (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source      TEXT NOT NULL,       -- 'spring-ai-docs', 'spring-ai-github'
    version     TEXT NOT NULL,       -- '0.8', '1.0-GA', '1.1+'
    section     TEXT,                -- e.g. 'ChatClient', 'EmbeddingModel'
    url         TEXT,
    content     TEXT NOT NULL,
    content_hash TEXT NOT NULL UNIQUE, -- SHA-256 of content; used for idempotent upsert
    embedding   VECTOR(1536),        -- text-embedding-3-small output dimension
    content_tsv TSVECTOR             -- generated column for BM25 full-text search
        GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    ingested_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX ON chunks USING ivfflat (embedding vector_cosine_ops);
CREATE INDEX ON chunks USING GIN (content_tsv);
CREATE INDEX ON chunks (version);
```

### `eval_runs` — CI evaluation history

```sql
CREATE TABLE eval_runs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_at      TIMESTAMPTZ DEFAULT now(),
    git_sha     TEXT,
    total       INT,
    passed      INT,
    failed      INT,
    pass_rate   NUMERIC(5,2),
    results     JSONB   -- per-question detail: id, question, pass/fail, actual vs expected
);
```

No `conversations` table in v1. Atlas is stateless per request — no session continuity. Multi-turn context is a v2 question.

---

## Ingestion pipeline (`atlas-ingestion`)

Three source adapters feeding one shared pipeline. Output is a unified `Chunk` domain object.

```
Source adapters
  ├── GitHubRepoAdapter
  │     Clones / pulls spring-projects/spring-ai (Apache 2.0)
  │     Walks /docs/**, README.md, CHANGELOG.md
  │     Tags chunks with version derived from CHANGELOG section headings
  │
  ├── DocsWebCrawler
  │     Crawls spring.io/projects/spring-ai
  │     Respects robots.txt
  │     Stores raw HTML; strips to text before chunking
  │
  └── ChangelogAdapter
        Parses CHANGELOG.md
        Tags each entry with the version it belongs to
        Useful for "what changed in 1.0-GA" queries

         │  raw text + metadata { source, version, section, url }
         ▼

ChunkingService
  Strategy v1: fixed-size
    window: 512 tokens
    overlap: 64 tokens
  Strategy v2 (deferred): section-aware
    split on ## headings
    fallback to fixed-size if section > 1024 tokens
  Decision: start with fixed-size; measure retrieval quality against eval set;
            switch to section-aware only if quality is demonstrably poor

         │  List<RawChunk>
         ▼

EmbeddingService
  Model: text-embedding-3-small via Spring AI EmbeddingClient
  Batch size: 100 chunks per API call (rate limit headroom)
  Idempotent: skips chunks where content_hash already exists in DB

         │  List<Chunk> with embeddings
         ▼

ChunkRepository (Spring Data JPA)
  Upsert by content_hash
  Logs: chunks written, chunks skipped, total duration
```

Ingestion is a one-time load plus periodic refresh. It is not in the query path.

---

## Retrieval layer (`atlas-retrieval`)

Hybrid search with Reciprocal Rank Fusion (RRF) as the late-fusion step.

### Query flow

```
Input: { query: string, version: string }

         ├──► BM25 path (PostgreSQL full-text search)
         │     SELECT id, content, url, section,
         │            ts_rank(content_tsv, plainto_tsquery('english', :query)) AS bm25_score
         │     FROM chunks
         │     WHERE content_tsv @@ plainto_tsquery('english', :query)
         │       AND version = :version
         │     ORDER BY bm25_score DESC
         │     LIMIT 20
         │
         └──► Dense path (PGVector cosine similarity)
               SELECT id, content, url, section,
                      1 - (embedding <=> :queryEmbedding) AS cosine_score
               FROM chunks
               WHERE version = :version
               ORDER BY cosine_score DESC
               LIMIT 20

                    │  two ranked lists (up to 20 results each)
                    ▼

              Reciprocal Rank Fusion
                combined_score(d) = Σ  1 / (k + rank_in_list)
                k = 60  (standard published constant; tunable against eval set)

                    │  re-ranked unified list
                    ▼

              Top 5 chunks returned to caller
```

RRF requires no weight tuning and handles disagreement between BM25 and dense results cleanly. It is the default choice; a weighted linear combination is the alternative if the eval set reveals systematic problems with RRF.

### Version filter

`version` is an exact-match metadata filter applied before both searches. Supported values: `0.8`, `1.0-GA`, `1.1+`. If the user does not specify a version, the query runs unfiltered and results are tagged with their source version in the response.

---

## Agent layer (`atlas-agent`)

ReAct pattern: the model reasons about what it needs, calls a tool, observes the result, and repeats until it has enough to answer.

### Tools

```java
@Tool(description = """
    Search the Spring AI documentation. Call this before answering any factual question
    about APIs, configuration, or behavior. Pass the user's question as the query.
    """)
SearchResult searchDocs(String query, String version) { ... }
// Delegates to atlas-retrieval. Returns top-5 chunks with section and URL.

@Tool(description = """
    Compile a Java code snippet. Call this to verify any code example before including
    it in a response. Returns success or a list of compiler errors.
    """)
CompileResult compileJavaSnippet(String code) { ... }
// Uses javax.tools (Java Compiler API) in-process.
// Wraps snippet in a minimal class if no class declaration is present.
// Returns: { compiled: true } or { compiled: false, errors: ["..."] }

@Tool(description = """
    Fetch a Spring AI GitHub issue by number. Call this only when the question
    explicitly references a specific issue number.
    """)
GithubIssue fetchGithubIssue(int issueNumber) { ... }
// Calls GitHub REST API: GET /repos/spring-projects/spring-ai/issues/{number}
// Unauthenticated; rate-limit aware (checks X-RateLimit-Remaining header).
// Returns: { title, body, state, labels[] }
```

### Agent loop

```
System prompt (prompt-cached prefix):
  ┌─────────────────────────────────────────────────────────────┐
  │ Role and behavior instructions (~350 tokens)                │
  │ Structured output schema definition (~200 tokens)           │
  │ Framework context block (~800 tokens)                       │
  │   — Spring AI architecture overview                         │
  │   — Key classes per version                                 │
  │   — Common migration patterns 0.8 → 1.0-GA → 1.1+          │
  └─────────────────────────────────────────────────────────────┘
  Total cached prefix: ~1,350 tokens (above Anthropic's 1,024 token minimum)

Turn sequence (typical factual + code question):
  1. User query arrives
  2. Model → calls searchDocs(query, version)
  3. Tool result: top-5 chunks with citations
  4. Model → calls compileJavaSnippet(code draft)
  5. Tool result: compile success or errors
  6. If errors: model revises code, calls compileJavaSnippet again
  7. Model produces final structured JSON answer

Model selection:
  Tool-call steps (turns 2–6): Claude Haiku  (cost-efficient for tool dispatch)
  Final answer generation (turn 7): Claude Sonnet  (quality for the user-visible response)
```

The agent does not have a hard step limit in v1. If the model loops more than 5 times on a single query, that is a tuning signal, not a reason to add an arbitrary cutoff.

---

## Structured output schema

Every response conforms to this schema. Enforced by Spring AI's `BeanOutputConverter`.

```json
{
  "answer":       "string  — prose explanation",
  "code_block":   "string | null  — Java code example; null if not applicable",
  "imports":      ["string"],
  "maven_dep":    "string | null  — <dependency> XML block",
  "gradle_dep":   "string | null  — implementation('...') line",
  "version_tag":  "0.8 | 1.0-GA | 1.1+",
  "citations": [
    {
      "section": "string",
      "url":     "string",
      "excerpt": "string  — exact chunk text the answer is grounded in"
    }
  ]
}
```

If the model does not conform, `BeanOutputConverter` throws. The API returns HTTP 422 with the raw model output logged for inspection. Non-conformance events are a metric: if the rate exceeds 2%, the system prompt needs revision.

---

## Prompt caching

Anthropic's prompt caching requires the cached prefix to be ≥ 1,024 tokens. The system prompt alone is ~350 tokens. The framework context block (~800 tokens) is appended immediately after and is static across requests — making the combined prefix ~1,350 tokens and eligible for caching.

```
Request structure:
  [System prompt ~350 tokens     ]  ─┐
  [Framework context block ~800t ]   ├─ cached prefix (~1,350 tokens)
  [User query — varies           ]  ─┘  not cached
```

Cache TTL on Anthropic's side: 5 minutes. Atlas holds the context block in memory; no re-injection cost within a JVM session.

Metrics tracked per request:
- `atlas.cache.hit` / `atlas.cache.miss` (Micrometer counter)
- `atlas.latency.ms` tagged by cache hit/miss
- `atlas.cost.cents` tagged by cache hit/miss

These metrics go to a Micrometer registry. In local dev: logged to console. In a deployed environment: exportable to Prometheus/Grafana.

---

## Streaming (`atlas-api`)

Two endpoints:

| Endpoint | Behavior |
|---|---|
| `POST /api/v1/ask` | Waits for full response; returns complete structured JSON |
| `GET  /api/v1/ask/stream` | SSE; streams tokens as they arrive; sends `event: done` with full structured object on completion |

Spring AI's `ChatClient.stream()` returns `Flux<String>`. The SSE endpoint wraps this in a `SseEmitter`. The minimal React UI subscribes to the SSE endpoint, renders tokens as they arrive, and replaces partial text with the structured response on `event: done`.

---

## Eval runner (`atlas-evals`)

### Dataset format

File: `atlas-evals/src/test/resources/evals/spring-ai-qa.json`

```json
[
  {
    "id": "sa-001",
    "question": "How do I create a ChatClient in Spring AI 1.0-GA?",
    "version": "1.0-GA",
    "expected_citations": ["ChatClient", "ChatClient.Builder"],
    "expected_code_compiles": true,
    "reference_answer": "Use ChatClient.builder(chatModel).build()...",
    "min_similarity": 0.80
  }
]
```

v1 target: 50 questions covering:
- API usage per version (ChatClient, EmbeddingClient, VectorStore, Advisors)
- Migration questions (0.8 → 1.0-GA breaking changes, 1.0-GA → 1.1+ additions)
- Configuration questions (application.properties keys, auto-configuration)
- Code examples that must compile

### Scoring per question

| Check | Method | Pass condition |
|---|---|---|
| Citation present | Section name in `citations[].section` | Expected section appears |
| Code compiles | Run `code_block` through compile tool | No compiler errors |
| Answer similarity | Cosine sim of embedded answer vs embedded reference | ≥ `min_similarity` |

Overall pass rate threshold: **80%**. Below 80% fails the CI job and blocks the PR.

### CI integration

`atlas-evals` is a JUnit 5 test class. GitHub Actions workflow:

```yaml
- name: Run evals
  run: mvn test -pl atlas-evals
```

Results written to `eval_runs` table. PR comment posts the pass rate and a link to the failed questions.

---

## MCP server (`atlas-mcp`)

Exposes one tool to MCP clients:

```
Tool name:   ask_atlas
Parameters:  { query: string, version: string }
Returns:     ToolResult containing the full structured JSON response
Transport:   stdio
```

`atlas-mcp` is a second Spring Boot app. It starts, reads JSON-RPC messages from stdin, writes responses to stdout, and delegates to `atlas-retrieval` and `atlas-agent` as shared library modules.

Claude Desktop configuration (`~/.config/claude/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "atlas": {
      "command": "java",
      "args": ["-jar", "/path/to/atlas-mcp.jar"]
    }
  }
}
```

HTTP/SSE MCP transport (for remote clients) is deferred to v2.

---

## Implementation sequence

Each step has a clear exit condition. No step begins until the previous step's exit condition is met.

| Step | Module | What gets built | Exit condition |
|---|---|---|---|
| 1 | `infra/` | PostgreSQL + PGVector via docker-compose; schema.sql applied | `psql` connects; `\d chunks` shows correct schema |
| 2 | `atlas-core` | `Chunk`, `Version`, structured output DTOs | Module compiles; unit tests pass |
| 3 | `atlas-ingestion` | Source adapters → chunker → embedder → ChunkRepository | 1,000+ chunks in DB; `SELECT count(*) FROM chunks` confirms |
| 4 | `atlas-retrieval` | BM25 + dense search + RRF | Top-5 retrieval for 10 hand-checked questions looks correct |
| 5 | `atlas-api` (basic) | `POST /api/v1/ask` — retrieve + generate, structured output, no agent | Returns valid structured JSON for 10 test questions |
| 6 | `atlas-api` (streaming) | SSE endpoint + minimal React UI | Browser shows token stream |
| 7 | `atlas-agent` | Three tools registered; ReAct agent loop | Agent calls `searchDocs` before answering; calls `compileJavaSnippet` before returning code |
| 8 | Prompt caching | Framework context block injected; cache metrics instrumented | Measured latency difference logged in console |
| 9 | `atlas-evals` | 50-question dataset; CI runner; GitHub Actions job | PR check visible; failing eval blocks merge |
| 10 | `atlas-mcp` | MCP server; stdio transport | Claude Desktop calls `ask_atlas` and receives a response |

Steps 1–6 produce a working RAG service. Steps 7–10 add agents, caching, evals, and MCP.

---

## Open decisions

These will be resolved during implementation, not upfront. Recorded here so they are not forgotten.

| # | Decision | Options | Current lean |
|---|---|---|---|
| D1 | Chunking strategy | Fixed-size (v1) vs section-aware (v2) | Fixed-size first; switch only if eval quality is poor |
| D2 | Hybrid fusion method | RRF (k=60) vs weighted linear combination | RRF — no calibration required |
| D3 | Cache threshold | System prompt alone (~350t) vs with context block (~1,350t) | Context block required to clear 1,024t minimum |
| D4 | Agent step model | Haiku for tool steps, Sonnet for final answer vs Sonnet throughout | Haiku/Sonnet split for cost; revisit if tool-selection accuracy is poor |
| D5 | MCP transport | stdio (v1) vs HTTP/SSE (v2) | stdio only in v1 |
| D6 | LangChain4j | v2 addition after Spring AI path is solid | Not in v1 scope |
| D7 | Non-conformance handling | 422 + log vs retry-with-feedback | 422 + log in v1; retry is a v2 option if non-conformance rate is high |
