# Atlas — Spring AI Learning Vehicle

A Q&A service over Spring AI documentation, built to learn RAG, tool calling, AI agents,
prompt caching, evals, MCP, streaming, and structured output — hands-on, in Java and Spring AI.

## What this is

Atlas answers questions about the Spring AI framework: API usage, version differences,
migration paths, configuration patterns. Each component of the implementation is chosen
to exercise a specific AI engineering technique from scratch.

## Techniques

| Technique | Module | Notes |
|---|---|---|
| RAG | `atlas-retrieval` | BM25 + PGVector hybrid search, RRF fusion |
| Tool calling | `atlas-agent` | `search_docs`, `compile_java_snippet`, `fetch_github_issue` |
| AI agents | `atlas-agent` | ReAct loop via Spring AI ChatClient |
| Prompt caching | `atlas-agent` | System prompt + context block cached; latency measured |
| Structured output | `atlas-agent` | JSON schema enforced via `BeanOutputConverter` |
| Streaming | `atlas-api` | SSE endpoint, token-by-token |
| Evals | `atlas-evals` | 50-question dataset; CI runner; PR check |
| MCP | `atlas-mcp` | stdio transport; works with Claude Desktop |

## Modules

```
atlas-core        Shared domain model: Chunk, Version, output DTOs
atlas-ingestion   CLI: crawl Spring AI docs + GitHub repo → chunk → embed → load
atlas-retrieval   Hybrid search: BM25 + PGVector + RRF fusion
atlas-agent       ReAct agent, tool calling, prompt caching, structured output
atlas-api         Spring Boot app: REST + SSE endpoints
atlas-mcp         MCP server: exposes ask_atlas tool over stdio
atlas-evals       Eval dataset and CI runner
```

## Dependency graph

```
atlas-core
    ├── atlas-ingestion
    ├── atlas-retrieval
    │       └── atlas-agent
    │               ├── atlas-api      (runnable)
    │               ├── atlas-mcp      (runnable)
    │               └── atlas-evals
```

## Prerequisites

- Java 21
- Maven 3.9+
- Docker (for PostgreSQL + PGVector)
- Anthropic API key (Claude — for agent)
- OpenAI API key (text-embedding-3-small — for embeddings)

## Local setup

```bash
# 1. Start PostgreSQL + PGVector
cd infra && docker-compose up -d

# 2. Run ingestion (loads Spring AI docs into the DB)
mvn exec:java -pl atlas-ingestion

# 3. Start the API
mvn spring-boot:run -pl atlas-api

# 4. Ask a question
curl -X POST http://localhost:8080/api/v1/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "How do I create a ChatClient in Spring AI 1.0-GA?", "version": "1.0-GA"}'
```

## Environment variables

```
ANTHROPIC_API_KEY     Claude API key (agent generation)
OPENAI_API_KEY        OpenAI API key (embeddings)
SPRING_DATASOURCE_URL jdbc:postgresql://localhost:5432/atlas
SPRING_DATASOURCE_USERNAME  atlas
SPRING_DATASOURCE_PASSWORD  atlas
```

## Build sequence

Implementation follows a deliberate sequence — each step has an exit condition before
the next begins. See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full design.

| Step | What gets built |
|---|---|
| 1 | Infra: PostgreSQL + PGVector running locally |
| 2 | `atlas-core`: domain model compiles, unit tests pass |
| 3 | `atlas-ingestion`: 1,000+ chunks loaded into DB |
| 4 | `atlas-retrieval`: top-5 retrieval correct for 10 hand-checked questions |
| 5 | `atlas-api` basic: returns structured JSON for 10 test questions |
| 6 | `atlas-api` streaming: browser shows token stream |
| 7 | `atlas-agent`: agent calls tools before answering |
| 8 | Prompt caching: measured latency difference logged |
| 9 | `atlas-evals`: PR check visible on GitHub, blocks on <80% pass rate |
| 10 | `atlas-mcp`: Claude Desktop calls `ask_atlas` successfully |

## Stack

- Java 21, Spring Boot 3.4.x, Spring AI 1.0.x
- PostgreSQL 16 + PGVector
- Anthropic Claude — Haiku (tool steps) + Sonnet (final answer)
- OpenAI text-embedding-3-small
- React + Vite (minimal streaming UI)

## License

Apache 2.0
