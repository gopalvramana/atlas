# Ingestion Pipeline

The ingestion pipeline is a one-time CLI process that fetches Spring AI documentation
from GitHub, processes it into searchable chunks, generates embeddings, and loads
everything into the PostgreSQL `chunks` table.

It runs independently of the API and can be safely re-run whenever documentation is updated.

---

## Pipeline Flow

```mermaid
flowchart TD
    classDef entry fill:#6366f1,stroke:#4338ca,color:#fff,font-weight:bold
    classDef fetch fill:#0ea5e9,stroke:#0284c7,color:#fff,font-weight:bold
    classDef process fill:#10b981,stroke:#059669,color:#fff,font-weight:bold
    classDef embed fill:#f59e0b,stroke:#d97706,color:#fff,font-weight:bold
    classDef store fill:#ef4444,stroke:#dc2626,color:#fff,font-weight:bold
    classDef decision fill:#8b5cf6,stroke:#7c3aed,color:#fff,font-weight:bold
    classDef record fill:#64748b,stroke:#475569,color:#fff,font-weight:bold

    A([🚀 IngestionCli\nEntry Point]):::entry
    B[/For each version\n1.0-GA · 1.1 · 2.0-M/]:::decision

    subgraph FETCH ["📥 Step 1 — Fetch"]
        C[GitHubDocsFetcher\nList include-paths via Contents API]:::fetch
        D[GitHubDocsFetcher\nFetch raw .adoc content via Raw URL]:::fetch
    end

    subgraph EXTRACT ["🔤 Step 2 — Extract"]
        E[AsciiDocAdapter\n.adoc → AsciidoctorJ → HTML]:::process
        F[AsciiDocAdapter\nJsoup strips HTML → plain text\nImage alt text preserved inline]:::process
    end

    subgraph CHUNK ["✂️ Step 3 — Chunk"]
        G[ChunkingService\njtokkit encodes full text → token IDs]:::process
        H[ChunkingService\nSliding window — size 512, step 448\nDecode each window → chunk text]:::process
        I[ChunkingService\nSHA-256 content_hash per chunk\nTag: source · version · section · url]:::process
    end

    subgraph EMBED ["🧠 Step 4 — Embed"]
        J[EmbeddingService\nBatch 100 chunks per API call]:::embed
        K{Rate limit\nHTTP 429?}:::decision
        L[Exponential backoff\nRetry up to 3 times]:::embed
        M[OpenAI text-embedding-3-small\nfloat 1536 per chunk]:::embed
    end

    subgraph STORE ["💾 Step 5 — Store"]
        N{document_hash\nchanged?}:::decision
        NA[Skip file —\nall chunks unchanged]:::store
        NB[DELETE old chunks\nfor url + version]:::store
        NC[ChunkJdbcWriter\nINSERT chunks\nON CONFLICT (content_hash, version) DO NOTHING]:::store
    end

    subgraph RECORD ["📊 Step 6 — Record"]
        R[IngestionRunRepository\nWrite ingestion_runs row]:::record
        S[Log: files fetched\nchunks produced · inserted · skipped\nduration · status]:::record
    end

    A --> B
    B --> C
    C --> D
    D --> E
    E --> F
    F --> G
    G --> H
    H --> I
    I --> J
    J --> K
    K -- Yes --> L
    L --> J
    K -- No --> M
    M --> N
    N -- Unchanged --> NA
    N -- Changed --> NB
    NB --> NC
    NA --> R
    NC --> R
    R --> S
    S --> B
```

---

## Step-by-step explanation

### Step 1 — Fetch

**Class:** `GitHubDocsFetcher`

Calls the GitHub REST API to retrieve `.adoc` files for each configured version.

Two API calls per file:

| Call | URL | Purpose |
|---|---|---|
| Contents API | `GET https://api.github.com/repos/spring-projects/spring-ai/contents/{path}?ref={branch}` | List files in a directory |
| Raw URL | `GET https://raw.githubusercontent.com/spring-projects/spring-ai/{branch}/{path}` | Fetch raw `.adoc` content |

Only files in `include-paths` are fetched. Files in `exclude-files` are skipped.

---

### Step 2 — Extract

**Class:** `AsciiDocAdapter` (implements `DocumentAdapter`)

Converts raw `.adoc` markup into clean plain text:

1. **AsciidoctorJ** converts `.adoc` → HTML — handles all AsciiDoc features correctly
2. Image macros (`image::file.png[alt text]`) → replaced with `[Image: alt text]` inline
3. **Jsoup** strips remaining HTML tags → clean plain text
4. Whitespace normalised

Result: searchable plain text with no markup noise.

---

### Step 3 — Chunk

**Class:** `ChunkingService`

Splits the full document text into overlapping token windows:

```
Full text encoded to token IDs by jtokkit (cl100k_base — same as text-embedding-3-small)

Sliding window:
  window size = 512 tokens
  step size   = 448 tokens  (512 - 64 overlap)

Example — 2,000 token document:
  Chunk 1: tokens    0 →  512
  Chunk 2: tokens  448 →  960
  Chunk 3: tokens  896 → 1,408
  Chunk 4: tokens 1,344 → 1,856
  Chunk 5: tokens 1,792 → 2,000  ← last chunk, smaller than 512
```

Each chunk is tagged with:
- `source` — `SPRING_AI_GITHUB`
- `version` — e.g. `1.0-GA`
- `section` — derived from filename e.g. `chatclient`
- `url` — GitHub `html_url` of the source file
- `contentHash` — SHA-256 of chunk text (chunk-level duplicate guard)
- `documentHash` — SHA-256 of the full source document (document-level idempotency; same value on every chunk from the same file)

---

### Step 4 — Embed

**Class:** `EmbeddingService`

Generates a 1536-dimensional vector for each chunk using OpenAI `text-embedding-3-small`.

- Chunks are sent in batches of 100 per API call
- HTTP 429 (rate limit) triggers exponential backoff and retry (up to 3 attempts)
- HTTP 5xx triggers retry (up to 3 attempts), then fails the run

---

### Step 5 — Store

**Classes:** `IngestionService` · `ChunkRepository` · `ChunkJdbcWriter`

Decision is made at the **document level** using `document_hash` (SHA-256 of the full `.adoc` file):

| `document_hash` | Action |
|---|---|
| Unchanged | Skip entire file — all chunks are identical, nothing to do |
| Changed | DELETE all chunks for `url + version`, re-chunk, re-embed, INSERT fresh chunks |
| Not found | First time seeing this file — chunk, embed, insert |

`ON CONFLICT (content_hash, version) DO NOTHING` is the chunk-level idempotency guard.
The conflict target is scoped to `(content_hash, version)` so that identical content shared
between versions (e.g. a file unchanged from 1.0-GA to 1.1) is stored once **per version** —
each version has a complete and independent set of chunks in the DB.

This ensures:
- Stale chunks never accumulate when a document is updated
- No version is silently incomplete due to cross-version content deduplication

---

### Step 6 — Record

**Class:** `IngestionRunRepository`

Writes one row to `ingestion_runs` per version processed:

| Field | Value |
|---|---|
| `source` | `spring-ai-github` |
| `version` | e.g. `1.0-GA` |
| `branch` | e.g. `1.0.x` |
| `files_fetched` | total files retrieved from GitHub |
| `chunks_produced` | total chunks after splitting |
| `chunks_inserted` | new chunks written to DB |
| `chunks_skipped` | files skipped because document_hash was unchanged |
| `duration_ms` | total time for this version |
| `status` | `SUCCESS` or `FAILED` |
| `error_message` | populated if `FAILED` |

---

## Error handling

| Scenario | Behaviour |
|---|---|
| GitHub API rate limit (HTTP 429) | Exponential backoff, retry up to 3 times |
| GitHub API failure (HTTP 5xx) | Retry up to 3 times, then fail the run |
| OpenAI rate limit (HTTP 429) | Exponential backoff, retry up to 3 times |
| OpenAI failure | Retry up to 3 times, then fail the run |
| `document_hash` unchanged | Skip entire file — document not modified since last run |
| `document_hash` changed | DELETE old chunks for url+version, re-chunk, re-embed, insert fresh |
| Run fails mid-way | Already-inserted chunks remain. Next run sees document_hash changed, deletes partial chunks, and reprocesses cleanly |

---

## Configuration reference

```yaml
atlas:
  ingestion:
    chunk-size: 512           # tokens per chunk
    chunk-overlap: 64         # overlap tokens between consecutive chunks
    batch-size: 100           # chunks per OpenAI embedding API call
    github:
      owner: spring-projects
      repo: spring-ai
      docs-path: spring-ai-docs/src/main/antora/modules/ROOT/pages
      token: ${GITHUB_TOKEN}  # personal access token — 5,000 requests/hour
      versions:
        - label: "1.0-GA"
          branch: "1.0.x"
        - label: "1.1"
          branch: "1.1.x"
        - label: "2.0-M"
          branch: "main"
      include-paths:
        - api
        - guides
        - concepts.adoc
        - getting-started.adoc
        - upgrade-notes.adoc
      exclude-files:
        - contribution-guidelines.adoc
        - index.adoc
```

---

## Running the pipeline

```bash
# Copy .env.example to .env and fill in your values
cp .env.example .env
# edit .env: set GITHUB_TOKEN, OPENAI_API_KEY, DB_URL, DB_USERNAME, DB_PASSWORD

# Run ingestion
OPENAI_KEY=$(grep OPENAI_API_KEY .env | cut -d= -f2) && \
  mvn exec:java -pl atlas-ingestion -Dspring.ai.openai.api-key="$OPENAI_KEY"
```

> **Note:** The `-Dspring.ai.openai.api-key` flag is required because Spring AI validates
> the key during autoconfiguration before `.env` values are available in the Spring context.

Expected console output (first run):

```
INFO  IngestionService : === Ingesting version: 1.0-GA (branch: 1.0.x) ===
INFO  IngestionService : Fetched 29 files for version 1.0-GA
INFO  EmbeddingService : Embedding 7 chunks in 1 batch(es) of up to 100
INFO  IngestionService :   ✓ advisors.adoc → 7 chunks produced, 7 inserted
INFO  IngestionService :   ✓ chatclient.adoc → 12 chunks produced, 12 inserted
...
INFO  IngestionService : Version 1.0-GA done — files=29, produced=167, inserted=167, skipped=0, 12437ms

INFO  IngestionService : === Ingesting version: 1.1 (branch: 1.1.x) ===
...
INFO  IngestionService : Version 1.1 done — files=33, produced=192, inserted=192, skipped=0, 17267ms
```

Expected console output (re-run — unchanged documents):

```
INFO  IngestionService : Version 1.0-GA done — files=29, produced=0, inserted=0, skipped=29, 1809ms
```

**Verified DB counts (first full run):**

| Version | Branch | Files | Chunks |
|---|---|---|---|
| 1.0-GA | 1.0.x | 29 | 167 |
| 1.1 | 1.1.x | 33 | 192 |
| 2.0-M | main | 33 | 211 |
| **Total** | | **95** | **570** |
