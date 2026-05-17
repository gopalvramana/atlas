# Interview Prep — Atlas Ingestion Pipeline

Questions you should be ready to answer based on exactly what was built.
Grouped by topic. Answers are grounded in the actual implementation — not theory.

---

## 1. Chunking Strategy

**Q: Why did you choose 512 tokens as chunk size? What happens if it's too large or too small?**

512 tokens (~380 words) is a well-established sweet spot for `text-embedding-3-small`.
Too large → the embedding averages over too many concepts, making it semantically vague.
Too small → individual chunks lack enough context to be useful in retrieval.
512 fits most Spring AI doc sections cleanly without losing structure.

**Q: What is sliding window chunking and why do you use overlap?**

Instead of cutting the document into hard non-overlapping blocks, a sliding window moves
forward by a step smaller than the window size. Each chunk overlaps with its neighbours.
This ensures sentences that fall near a boundary appear in full in at least one chunk —
preventing a concept from being split across two chunks and lost in retrieval.

**Q: Why 64 tokens of overlap? What's the trade-off?**

64 tokens (~12% of 512) is enough to preserve boundary continuity without bloating chunk
count significantly. More overlap → better boundary coverage but more chunks and more
OpenAI API cost. Less overlap → cheaper but risks missing split concepts.

**Q: Why token-based chunking instead of character or sentence splitting?**

The embedding model (`text-embedding-3-small`) has a hard token limit — not a character
limit. Chunking by tokens guarantees no chunk ever exceeds the model's context window.
Sentence splitting can produce very uneven chunk sizes and risks token limit violations.

**Q: Why `cl100k_base` encoding specifically?**

`text-embedding-3-small` uses `cl100k_base` internally. Using the same tokeniser at
chunking time means token counts at ingestion exactly match what the model sees — no
off-by-one drift. This is why `jtokkit` was chosen over a generic character estimator.

**Q: What happens to a very short document — say, only 100 tokens?**

The sliding window produces a single chunk covering the full document. The step size is
448, so one pass covers tokens 0→512. The chunking loop terminates after one window.

---

## 2. Embeddings

**Q: What is a vector embedding and what does it represent?**

A vector embedding is a fixed-length array of floating point numbers that encodes the
semantic meaning of a piece of text. Texts with similar meaning are close together in
vector space (small cosine distance). `text-embedding-3-small` outputs 1536 floats per
chunk — one point in 1536-dimensional space.

**Q: Why `text-embedding-3-small` and not `text-embedding-3-large`?**

`text-embedding-3-small` (1536 dims) gives strong retrieval quality at ~5× lower cost
and latency than `text-embedding-3-large` (3072 dims). For a documentation RAG system
the quality difference is negligible; the cost difference is significant at scale.

**Q: What does each dimension of the 1536-dimensional vector mean?**

Nothing interpretable individually. The dimensions are learned by the model during
training — they collectively encode semantic position in the model's latent space.
You cannot inspect a single dimension and assign it a human-readable meaning.

**Q: How does cosine similarity work? Why use it over Euclidean distance for text?**

Cosine similarity measures the angle between two vectors — it is 1.0 for identical
direction, 0.0 for orthogonal. It ignores magnitude, which matters for text because
longer documents produce larger magnitude vectors without being "more similar".
Euclidean distance conflates magnitude with direction, making it less reliable for
semantic search over variable-length texts.

**Q: Why batch 100 chunks per API call?**

OpenAI's embedding API accepts up to 2048 inputs per call. 100 is a practical batch
size that keeps each API call fast, limits the blast radius of a retry, and stays well
within rate limits. Sending one chunk per call would be ~100× slower for a 10,000 chunk
corpus.

---

## 3. Idempotency Design

**Q: What is idempotency and why does it matter for an ingestion pipeline?**

An idempotent operation produces the same result regardless of how many times it runs.
For ingestion this means: re-running the pipeline on unchanged documents must not
duplicate data, waste API calls, or corrupt the knowledge base. Without idempotency,
every scheduled re-run would double the chunk count and cost.

**Q: Explain your two-level idempotency strategy.**

| Level | Mechanism | Scope |
|---|---|---|
| Document | `document_hash` (SHA-256 of raw `.adoc`) | Entire file |
| Chunk | `ON CONFLICT (content_hash, version) DO NOTHING` | Individual chunk row |

Document-level: if the file hasn't changed since last ingestion, skip it entirely —
no chunking, no embedding calls, no DB writes.

Chunk-level: if the file changed (or is new), re-chunk and re-embed, then insert.
The `ON CONFLICT` clause is a silent DB safety net that prevents a duplicate row if the
same chunk content appears in two different runs for the same version.

**Q: Why is the `ON CONFLICT` target `(content_hash, version)` and not just `content_hash`?**

A file that is identical between versions (e.g. `aimetadata.adoc` unchanged from 1.0-GA
to 1.1) produces chunks with the same `content_hash`. If the constraint were just
`UNIQUE (content_hash)`, the 1.1 row would be silently rejected — leaving version 1.1
incomplete in the DB. Scoping to `(content_hash, version)` allows the same chunk content
to exist once per version, while still preventing duplicate rows within a single version.

**Q: What bug would have occurred with `UNIQUE (content_hash)` alone?**

Queries filtered by `version = '1.1'` would return incomplete results for any document
shared with 1.0-GA. The system would silently serve partial answers for 1.1 — no error,
no warning. In our first run this caused 1.1 to have 100 chunks instead of 192.

**Q: What happens if the pipeline crashes halfway through a document?**

Partially inserted chunks remain in the DB. On the next run, `findDocumentHash` finds no
complete set for that `url + version` (or finds a different hash if the file changed).
The pipeline deletes all existing chunks for that `url + version` via
`deleteByUrlAndVersion`, then reprocesses the file cleanly from scratch.

---

## 4. pgvector / Database

**Q: What is pgvector and how does it store vectors?**

pgvector is a PostgreSQL extension that adds a native `VECTOR(n)` column type and
vector-specific operators (`<=>` for cosine distance, `<->` for L2, `<#>` for inner
product). Vectors are stored as compact binary arrays alongside regular relational data —
no separate vector database needed.

**Q: What is an IVFFlat index? What does `lists = 100` mean?**

IVFFlat (Inverted File with Flat compression) is an approximate nearest-neighbour index.
It clusters the vector space into `lists` Voronoi cells at build time. At query time,
only the nearest `probes` cells are searched rather than the full table.
`lists = 100` means the index partitions the space into 100 clusters. pgvector recommends
`lists = rows / 1000` (min 100) as a starting point.

**Q: When does IVFFlat hurt performance?**

When the table has very few rows. The index is built on existing data — with little data,
clusters are poorly formed and recall drops. pgvector warns about this ("created with
little data"). We saw this warning on first insert because the table was nearly empty.
The fix is to build the index after the bulk load, not before.

**Q: IVFFlat vs HNSW — what's the difference?**

| | IVFFlat | HNSW |
|---|---|---|
| Build time | Fast | Slow |
| Query speed | Fast | Faster |
| Recall | Good | Better |
| Memory | Low | High |
| Supports `INSERT` incrementally | Yes (but degrades) | Yes (stays accurate) |

HNSW is generally preferred for production. IVFFlat is fine for a corpus of ~570 chunks.

**Q: Why JDBC for the insert instead of JPA/Hibernate?**

JPA cannot express `ON CONFLICT (content_hash, version) DO NOTHING` in JPQL. It also
cannot cast a `String` to pgvector's `VECTOR` type using the `?::vector` syntax required
by PostgreSQL. Raw `JdbcTemplate` gives full control over the SQL, the conflict clause,
and the batch size — necessary for correctness and performance.

---

## 5. SOLID & Design

**Q: Walk me through how you applied Single Responsibility Principle.**

Each class has exactly one reason to change:

| Class | Single responsibility |
|---|---|
| `GitHubDocsFetcher` | Fetch raw `.adoc` from GitHub API |
| `AsciiDocAdapter` | Convert `.adoc` markup to plain text |
| `ChunkingService` | Split text into token windows + hash |
| `EmbeddingService` | Call OpenAI embedding API with retry |
| `ChunkJdbcWriter` | Write chunks to DB via JDBC |
| `ChunkRepository` | Query/delete chunks via Spring Data |
| `IngestionService` | Coordinate the pipeline steps |
| `IngestionCli` | Boot Spring and trigger ingestion |

If the embedding model changes, only `EmbeddingService` changes.
If the chunking algorithm changes, only `ChunkingService` changes.

**Q: Why is `ChunkingService` separate from `EmbeddingService`?**

They have different reasons to change and different dependencies.
`ChunkingService` depends on `jtokkit` (tokeniser). `EmbeddingService` depends on
OpenAI's API. Combining them would violate SRP — a tokeniser change would force
re-testing the embedding logic and vice versa. They are also independently testable in
isolation.

**Q: Why does `IngestionService` exist — why not put everything in `IngestionCli`?**

`IngestionCli` is a Spring Boot entry point — its job is to start the application and
hand off. Putting pipeline logic there mixes infrastructure concerns (Spring Boot
lifecycle) with business logic (fetch/chunk/embed/store). `IngestionService` is also
independently testable and reusable — e.g. it could be triggered by a REST endpoint or
a scheduled job without touching `IngestionCli`.

**Q: What is the `DocumentAdapter` interface for?**

Open/Closed Principle. Today we have `AsciiDocAdapter` for `.adoc` files. If we add
a Markdown source or a PDF crawler, we add a new `DocumentAdapter` implementation —
no existing code changes. `IngestionService` depends on the abstraction, not the
concrete class.

**Q: Why constructor injection over field injection?**

Constructor injection makes dependencies explicit and mandatory — the object cannot be
created in an invalid state. Fields are `private final` which enforces immutability.
Field injection (`@Autowired` on a field) hides dependencies, requires reflection, and
makes the class harder to test without a Spring context.

---

## 6. Spring Boot Internals

**Q: What is `EnvironmentPostProcessor` and why did you need it?**

`EnvironmentPostProcessor` is a Spring Boot hook that fires before any beans are created
— even before `@Configuration` classes are processed. We needed it to load `.env` values
into the Spring `Environment` early enough that autoconfiguration (which validates the
OpenAI API key on startup) could see them.

**Q: Why can't you call `Dotenv.load()` in `main()` before `SpringApplication.run()`?**

`SpringApplication.run()` triggers autoconfiguration internally during startup. By the
time your next line in `main()` would run, it's too late — autoconfiguration has already
tried to validate the API key and failed. `EnvironmentPostProcessor` hooks in during
the startup sequence, before autoconfiguration fires.

**Q: What is `@ConfigurationProperties` and why is it better than `@Value`?**

`@ConfigurationProperties` binds a structured block of YAML/properties to a typed Java
object with full validation support. `@Value` binds one property at a time to a field —
it becomes unwieldy with 10+ related properties, doesn't support nested structures
cleanly, and scatters configuration across the class. `@ConfigurationProperties` is
a single place to see all config for a feature.

**Q: What is `ApplicationRunner` and when does it fire?**

`ApplicationRunner` is a Spring Boot callback interface. Its `run()` method fires after
the application context is fully started and all beans are initialised — but before the
process exits. Used in CLI tools to execute logic on startup without building a
persistent server.

---

## 7. Error Handling & Resilience

**Q: What is exponential backoff and why is it right for rate limits?**

Exponential backoff doubles the wait time between retries: 2s → 4s → 8s.
For rate limits (HTTP 429), the server is telling you to slow down. Retrying immediately
just hits the limit again. Exponential backoff gives the rate limit window time to reset
before each retry — it's respectful of the API contract.

**Q: What is the difference between a retryable and a fatal error?**

| Retryable | Fatal |
|---|---|
| HTTP 429 — rate limit (temporary) | HTTP 401 — invalid API key (config error) |
| HTTP 500/503 — server error (transient) | HTTP 400 — bad request (programming error) |
| Network timeout | Missing required configuration |

Retryable errors are temporary — waiting and retrying is likely to succeed.
Fatal errors are permanent — retrying will always fail and the right action is to
log clearly and abort.

**Q: What happens to already-inserted chunks if the pipeline fails mid-run?**

They remain in the DB — partial data for the affected document. On the next run,
`findDocumentHash` either finds no hash for that url+version (if no chunks were inserted)
or finds an incomplete set. In either case, `deleteByUrlAndVersion` cleans up the partial
data before re-inserting. The pipeline is self-healing across runs.

---

## 8. System Design (broader)

**Q: Why crawl GitHub directly instead of using the Spring AI documentation website?**

The GitHub repository is the source of truth — structured, version-controlled, one
`.adoc` file per topic. The website is generated HTML with navigation chrome, ads, and
layout noise that degrades chunk quality. GitHub also provides a stable REST API with
version/branch filtering, which makes multi-version ingestion straightforward.

**Q: How would you handle a document being deleted from GitHub?**

Current design does not handle deletions — it only processes files it fetches. To handle
deletions: after fetching the file list for a version, query the DB for all `url` values
for that version, compute the difference (DB urls not in the fetched list), and delete
those chunks. This would be added to `IngestionService.ingestVersion()`.

**Q: How would you scale this to 10,000 documents?**

- Parallelise document processing with a thread pool (`ExecutorService` or virtual threads)
- Move to a queue-based architecture — each document is a message, workers process in parallel
- Cache GitHub API responses — avoid re-fetching unchanged files
- Use `document_hash` to skip unchanged files aggressively (already implemented)
- Pre-filter with BM25 before embedding to reduce OpenAI API calls

**Q: How would you run this on a nightly schedule?**

Option 1: Spring `@Scheduled` + convert `IngestionCli` to a long-running service.
Option 2: GitHub Actions cron job triggering `mvn exec:java`.
Option 3: Kubernetes CronJob.
`IngestionService` is already self-contained and reentrant — no changes needed to the
pipeline logic itself.

**Q: What would you change to support a second source — e.g. Spring Boot docs?**

Add a new `DocumentAdapter` implementation (e.g. `HtmlDocAdapter` for the Spring Boot
website). Add a new `Fetcher` (e.g. `WebCrawlerFetcher`). Add configuration under
`atlas.ingestion.sources`. `IngestionService` iterates sources — it would call each
fetcher/adapter pair. The chunk/embed/store pipeline is unchanged.

---

## Quick-fire answers to have ready

| Question | One-line answer |
|---|---|
| What is RAG? | Retrieve relevant context from a knowledge base, inject into LLM prompt, generate grounded answer |
| What is pgvector? | PostgreSQL extension for native vector storage and similarity search |
| What is cosine similarity? | Measures angle between vectors — 1.0 = identical direction, ignores magnitude |
| What is IVFFlat? | Approximate NN index — clusters vector space, searches only nearest clusters at query time |
| What is a token? | Smallest unit the LLM operates on — roughly ¾ of a word on average in English |
| Why SHA-256 for hashing? | Collision-resistant, deterministic, fast — ideal for idempotency checks |
| Why not store raw embeddings as JSONB? | JSONB has no vector operators — can't do cosine search natively without pgvector |
| What is Spring AI? | Spring abstraction over LLM providers — unified API for chat, embedding, vector store |
