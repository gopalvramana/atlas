# Architecture Decision Records

Every non-obvious design decision is recorded here.
Updated at every commit checkpoint — never at end of session only.

---

## ADR-001 — Two-turn conversation flow
**Date:** 2026-05-04
**Decision:** Stateless design — client carries state (selected version) between turns.
**Reason:** Simpler than server-side session management, scales horizontally, no session cleanup needed.
**Impact:** `AtlasQuery` carries `version` (nullable). Turn 1: version=null, search all. Turn 2: version=selected.

---

## ADR-002 — Spring AI over LangChain4j
**Date:** 2026-05-04
**Decision:** Spring AI 1.1.5 as primary AI framework. LangChain4j deferred to v2.
**Reason:** Native Spring Boot integration, consistent dependency model, production-ready for v1 scope.

---

## ADR-003 — DocumentAdapter interface for extensibility
**Date:** 2026-05-07
**Decision:** `DocumentAdapter` interface with `supports(String fileExtension)` + `extractText(String, String)`.
**Reason:** Open/Closed principle — adding HtmlAdapter, MarkdownAdapter later requires zero changes to existing classes.
**Implementations:** `AsciiDocAdapter` (built). `HtmlAdapter` deferred.

---

## ADR-004 — AsciidoctorJ + Jsoup for text extraction
**Date:** 2026-05-07
**Decision:** AsciidoctorJ 3.0.1 converts .adoc → HTML, then Jsoup 1.22.2 strips HTML → plain text.
**Reason:** Regex-based AsciiDoc stripping is too fragile. Two-step approach handles all AsciiDoc features correctly.
**Note:** AsciidoctorJ 3.x removed `headerFooter()` — use `standalone(false)` instead.

---

## ADR-005 — jtokkit for token counting
**Date:** 2026-05-07
**Decision:** jtokkit with `cl100k_base` encoding for token counting in ChunkingService.
**Reason:** Same encoding as OpenAI `text-embedding-3-small`. Accurate token boundaries — character-based splitting would produce inconsistent chunk sizes.

---

## ADR-006 — Two-level idempotency
**Date:** 2026-05-15
**Decision:** Document-level (`document_hash`) + DB-level (`content_hash ON CONFLICT DO NOTHING`).
**Reason:** `content_hash` alone is insufficient — when a document updates, chunk boundaries shift and stale chunks remain.
**Flow:**
- `document_hash` unchanged → skip entire file
- `document_hash` changed → DELETE all chunks for `url + version`, re-chunk, re-embed, insert fresh
- `ON CONFLICT DO NOTHING` is a silent DB guard against concurrent inserts — not a flow decision.

---

## ADR-007 — SOLID enforcement as a project standard
**Date:** 2026-05-16
**Decision:** Every class must have its single responsibility stated in one sentence before implementation. If it cannot be stated in one sentence, the design must be split.
**Reason:** Early violation caught — ChunkingService and EmbeddingService were incorrectly proposed to be combined.
**Rule:** State responsibility first. Code second.

---

## ADR-008 — Commits as the trigger for decisions.md + progress.md updates
**Date:** 2026-05-16
**Decision:** `decisions.md` and `progress.md` are updated at every commit checkpoint — not end of session.
**Reason:** No hook exists to automatically detect decisions from conversation. Commits are the natural, reliable trigger. `/remember` used mid-session for critical rules.
