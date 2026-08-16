# Plan — add an ELSER + Jina reranker search mode

**Status:** ✅ **implemented** — shipped as `SearchMode.ELSER_JINA`. See "As-built" below for where the
implementation deliberately departs from this plan, and what was skipped.
**Date:** 2026-08-15 (plan), verified as-built 2026-08-15
**Baseline:** Spring Boot 4.1.0, `elasticsearch-java` 8.17.0, five existing modes (TITLE, BM25, ELSER, E5, HYBRID)

---

## As-built — how the shipped code differs from this plan

Everything in §4 is implemented. Seven deliberate departures, and three items that were **not** done:

| Plan | As built | Why |
|---|---|---|
| Mode `ELSER_RERANK` (§4.1, decision 4) | **`ELSER_JINA`** | Names the actual reranker; it is in the public API `mode` enum, so effectively permanent |
| Two fields on `ElasticsearchProperties` (§4.2) | Separate **`RerankProperties`** record on `elasticsearch.rerank.*` | Cohesion — rerank settings are one concern. Also gained `@Min(1) @Max(1000)` validation the plan did not ask for |
| `rankWindowSize = max(W, from + size)` (§4.3) | **Window stays fixed at `W`**; the service clamps the page instead | The plan contradicted itself: §3 says the ceiling *is* `W` and "paging past `W` yields nothing", while §4.3 grows `W` to cover `from + size`, which removes that ceiling. Growing it per page would also change the candidate set and reshuffle a listwise ranking between pages. §3 won. The unit test is named `elserJinaKeepsTheRankWindowFixedWhenPagingDeeper` |
| `isRerankProviderRejected` / `isRerankRateLimited` booleans (§4.4) | One **`rerankRejectionReason()`** returning a user-facing reason, in `ElasticsearchErrors` | One call site, one branch, and the reason text ends up in the message. It also reads the **structured `ErrorCause` tree**, not `getMessage()` — the plan's string-matching assumption is broken for retrievers, which hide the cause in a suppressed exception — see `ElasticsearchErrors` and its test for the real error shape |
| "Keep a two-arg `of(...)` overload defaulting to `MAX_RESULT_WINDOW`" (§4.5) | **Overload removed** | It ended up used only by tests. All five other modes pass `MAX_RESULT_WINDOW` explicitly, which is clearer than an implicit default |
| Option C — your own Jina key (§1, decision 2) | **EIS endpoint** `eis-jina-reranker`, `service: elastic`, `model_id: jina-reranker-v3.5` | Elastic holds the credential, so no `JINA_API_KEY` enters the app at all. This is why §4.7's "add `JINA_API_KEY` to the configuration table" is moot |
| `jina-reranker-v3` (decision 1) | **`jina-reranker-v3.5`** | Drop-in upgrade; see the retracted §0 above |

### Not done

1. **The optional end-to-end rerank test** (§5, IT item 2) — `@EnabledIfEnvironmentVariable(named = "JINA_API_KEY")`,
   creating the endpoint and asserting the reranked order differs from the ELSER-only order. Item 1 (shape
   validation against a container with a deliberately missing endpoint) *is* implemented and passing.
   Consequence: **nothing in the suite proves the reranker actually reorders anything** — only that the
   cluster accepts the query we build and that failures are classified correctly.
2. **Phase 6, "Measure"** (§6) — p95 latency and result quality versus plain ELSER at `W` = 25/50/100.
   The plan calls this "the one people skip". It was skipped. The mode ships with no number attached.
3. **Phase 5's manual smoke test** on a populated index — the modes were never exercised against a cluster
   holding real movie data in the course of this work.

---

## 0. Two corrections before anything else

### ~~There is no `jina-reranker-3.5`~~ — **retracted, this was wrong**

> **Correction (2026-08-15).** This section originally claimed Jina's reranker line stopped at `v3` and
> that `3.5` did not exist. That was incorrect. **`jina-reranker-v3.5` is real and shipping**, and it is
> what this project now uses. The rest of this section is kept only so the mistake is on the record;
> read the corrected table below, not the retracted claim.

`jina-reranker-v3.5` is a 0.6B multilingual **listwise** reranker and a drop-in upgrade to v3 — same
request schema, so switching is a `model_id` change and nothing else. It keeps the "last but not late"
(LBNL) interface, ranking a query against the whole candidate list in one forward pass, and runs
1.22×–1.56× faster than v3.

| `model_id` | Size | Notes |
|---|---|---|
| **`jina-reranker-v3.5`** | 0.6B | **What this project uses.** Listwise, multilingual, 131 k token context shared by query + all candidates. Better on structured/legal/medical data than v3 |
| `jina-reranker-v3` | 0.6B | Previous listwise generation. Elastic documents it as reranking **at most 64 documents per call** — the constraint that shaped the original `window-size` guidance |
| `jina-reranker-v2-base-multilingual` | 278M | Cheaper/faster, scores documents independently so batches stay comparable |
| `jina-reranker-m0` | — | Multimodal |
| `jina-colbert-v2` | — | Late-interaction |

**On the document limit.** The "keep `window-size` ≤ 64" rule that this plan propagated into the code is
a **v3** figure. For v3.5 no fixed document cap is documented; the bound is the 131 k token context that
the query and every candidate share. With movie-length plots that leaves ample room above 50. The
guidance is now stated per-model in `RerankProperties` and `application.yml` rather than as a blanket rule.

### The good news: no client or version upgrade is needed

I verified against the jar on disk rather than assuming. `elasticsearch-java` 8.17.0 already models the
reranker retriever:

```
co.elastic.clients.elasticsearch._types.Retriever$Kind.TextSimilarityReranker
co.elastic.clients.elasticsearch._types.TextSimilarityReranker$Builder
    .retriever(...) .field(String) .inferenceId(String) .inferenceText(String) .rankWindowSize(Integer)
```

**But the 8.17 builder exposes only those five setters.** `min_score`, the reranker-level `filter`, and
`chunk_rescorer` exist in the REST API and are *not* reachable from this client version. That is fine —
filters already live on the child retriever in our `buildFilters` design — but it rules out `min_score`
as a relevance-tuning knob without either an ES client upgrade or hand-built JSON. I'd keep the typed
DSL (see [ADR-style note](#7-decisions-i-need-from-you)).

---

## 1. Elasticsearch-side prerequisite

A `rerank`-task inference endpoint. Three ways to get one, in increasing order of operational cost:

### Option A — Elastic's built-in reranker (no new secret, no egress)

`text_similarity_reranker` defaults to `.rerank-v1-elasticsearch` (the Elastic Rerank cross-encoder) when
`inference_id` is omitted. Requires the model to be deployed in the cluster, but needs **no API key and no
outbound network**.

### Option B — Elastic Inference Service preconfigured Jina endpoint

`.jina-reranker-v3` is offered as a preconfigured EIS endpoint, so Elastic manages the key. Availability
depends on your deployment type — **verify on your serverless project before relying on it.**

### Option C — your own Jina key (what "ELSER + Jina" literally implies)

```http
PUT _inference/rerank/jina-reranker-v3
{
  "service": "jinaai",
  "service_settings": {
    "api_key": "${JINA_API_KEY}",
    "model_id": "jina-reranker-v3"
  },
  "task_settings": {
    "top_n": 50,
    "return_documents": false
  }
}
```

Two operational gotchas:

- **The API key is write-only.** `GET _inference` never returns it, and it cannot be updated in place —
  rotating the key means deleting and recreating the endpoint under the same ID.
- `return_documents: false` keeps movie plots out of the inference response. We only need the reordering,
  and echoing document text back wastes payload.

> This repository has had live Elastic API keys committed to it before, so the Jina key must go in the environment or a secrets
> manager — **never** into `application.yml` or a `.run/*.xml`.

---

## 2. Which retrieval feeds the reranker

You said "ELSER + jina-reranker", which I read as a two-stage pipeline: ELSER retrieves, Jina reorders.
That's what this plan builds.

```
                    ┌─────────────────────────────┐
  query ──────────► │ semantic query on plot_elser│  top W candidates
                    │  (+ genre/year/rating filter)│
                    └──────────────┬──────────────┘
                                   ▼
                    ┌─────────────────────────────┐
                    │ text_similarity_reranker    │  cross-encoder scores
                    │ field=plot, inference_text=q│  (query, plot) pairs
                    └──────────────┬──────────────┘
                                   ▼
                            top `size` results
```

Target JSON:

```json
{
  "retriever": {
    "text_similarity_reranker": {
      "retriever": {
        "standard": {
          "query": { "semantic": { "field": "plot_elser", "query": "<q>" } },
          "filter": [ /* existing buildFilters output */ ]
        }
      },
      "field": "plot",
      "inference_id": "jina-reranker-v3",
      "inference_text": "<q>",
      "rank_window_size": 50
    }
  },
  "from": 0,
  "size": 50
}
```

**A variant worth considering** — wrap the *existing* RRF(BM25 + ELSER) in the reranker instead of bare
ELSER. That is more literally "hybrid" and usually beats single-retriever recall, at the cost of one extra
retrieval leg. It is a three-line change to the same method. I've listed it as a decision in §7 rather
than picking for you.

---

## 3. Constraints that shape the implementation

These are the things that will bite if the mode is bolted on naively.

| Constraint | Consequence |
|---|---|
| **`rank_window_size` defaults to 10** | Must be set explicitly or the mode silently returns at most 10 movies while the UI offers 25/50/100 per page. Non-negotiable. |
| **Only the top `rank_window_size` docs are reranked and returned** | Effective result ceiling for this mode is `W`, not `index.max_result_window`. Paging past `W` yields nothing. |
| **Top-level `sort` is illegal with *any* retriever** | Same rule that already forces HYBRID to degrade. This mode must degrade identically when a sort is chosen. |
| **Reranking is an external HTTP call per search** | Adds real p95 latency and per-token cost proportional to `W` × plot length. `W` is the cost dial. |
| **Scores are normalised** (`max(s,0) + min(exp(s),1)`) | Don't compare scores across modes or expose them as a quality metric. |
| **A bad/expired Jina key fails differently from a missing endpoint** | 401/403/429 from the provider surface as cluster errors that our current `isInferenceEndpointMissing` will *not* match, so they'd fall through to the generic "temporarily unavailable". Needs its own handling. |
| **8.17 client lacks reranker-level `filter`/`min_score`** | Filters go on the child retriever (already how we do it). |

The `W` ceiling is the interesting one: it maps cleanly onto machinery this codebase already has.
`MovieSearchPage.windowLimited` and the clamped `totalPages` exist precisely to stop the UI advertising
pages the server won't serve. Reuse them with `W` in place of `MAX_RESULT_WINDOW` for this mode rather
than inventing a second mechanism.

---

## 4. Code changes, file by file

### 4.1 `model/SearchMode.java`

```java
public enum SearchMode {
    TITLE, BM25, ELSER, E5, HYBRID, ELSER_RERANK;
```

Label: `"Hybrid (ELSER + Jina rerank)"`. The UI needs no change — both the landing page and the sidebar
iterate `SearchMode.values()`, so the new pill appears automatically. `fromString` already handles it.

### 4.2 `config/ElasticsearchProperties.java`

Two new bound properties, both with defaults so the mode degrades rather than NPEs:

```java
@DefaultValue("jina-reranker-v3") String rerankInferenceId,
@DefaultValue("50")               int    rerankWindowSize,
```

Making the inference ID configurable is what lets Options A/B/C in §1 all work with **zero code change** —
point it at `.rerank-v1-elasticsearch`, `.jina-reranker-v3`, or your own endpoint ID.
`application.yml` gains `rerank-inference-id: ${ELASTIC_RERANK_ID:jina-reranker-v3}`.

### 4.3 `repository/MovieRepository.java`

New branch in the `switch`, plus one method:

```java
case ELSER_RERANK -> searchElserRerank(queryText, from, size, filters, sort);

private SearchResult searchElserRerank(String queryText, int from, int size,
                                       SearchFilters filters, String sort) throws IOException {
    // Reranking cannot be combined with an attribute sort, exactly as with RRF.
    if (sort != null && !sort.isBlank()) {
        return searchBm25(queryText, from, size, filters, sort);
    }

    List<Query> esFilters = buildFilters(filters);
    int rankWindowSize = Math.max(esProperties.rerankWindowSize(), from + size);

    SearchResponse<Movie> response = esClient.search(s -> s
                    .index(esProperties.index())
                    .from(from)
                    .size(size)
                    .retriever(r -> r
                            .textSimilarityReranker(rr -> rr
                                    .retriever(inner -> inner
                                            .standard(st -> st
                                                    .query(q -> q.semantic(sem -> sem
                                                            .field("plot_elser")
                                                            .query(queryText)))
                                                    .filter(esFilters)))
                                    .field("plot")
                                    .inferenceId(esProperties.rerankInferenceId())
                                    .inferenceText(queryText)
                                    .rankWindowSize(rankWindowSize))),
            Movie.class
    );
    return toSearchResult(response, SearchMode.ELSER_RERANK);
}
```

Also extract the ELSER leg so `searchSemantic`, `searchHybrid` and this method share one definition of
"the ELSER query" instead of three copies of `.field("plot_elser")`.

### 4.4 `service/MovieService.java`

Three edits:

1. `inferenceEndpointName()` — add `case ELSER_RERANK -> "elser + " + rerankInferenceId` so the inline
   warning names the endpoint the user actually has to create.
2. **New failure classification.** A rejected Jina key currently lands in the generic
   "Search is temporarily unavailable". Add a sibling to `isInferenceEndpointMissing`:

   ```java
   static boolean isRerankProviderRejected(String message) { /* 401, 403, invalid api key */ }
   static boolean isRerankRateLimited(String message)      { /* 429, rate limit */ }
   ```

   mapped to a new `RerankUnavailableException extends ElastiflixException`
   (`errorCode: RERANK_UNAVAILABLE`) whose message tells the user to check the key or retry — and, in the
   UI, to fall back to plain ELSER. This matters because a reranker is the one mode with a *third-party*
   dependency; "temporarily unavailable" would send someone debugging their cluster instead of their key.
3. Cap the page for this mode at the rerank window so `clampPage` doesn't allow pages the reranker can
   never fill.

### 4.5 `model/MovieSearchPage.java`

`of(...)` currently hardcodes `MAX_RESULT_WINDOW` when computing `totalPages`/`windowLimited`. Parameterise
the ceiling so the reranked mode passes `W` instead:

```java
public static MovieSearchPage of(..., int reachableDocuments) { ... }
```

Keep a two-arg overload defaulting to `MAX_RESULT_WINDOW` so the other five modes are untouched. The
existing "only the first N of M matches can be paged through" notice then works for this mode for free.

### 4.6 Templates

`search.html` needs nothing structural — but two copy tweaks earn their keep:

- The degraded-mode notice says "Hybrid ranking (RRF) cannot be combined with an attribute sort". Make it
  mode-agnostic: "*Reranked and hybrid modes cannot be combined with an attribute sort*".
- The inference-missing warning suggests "switch to BM25 (Keyword)". For `ELSER_RERANK` the better
  suggestion is "switch to Semantic (ELSER)" — same recall, no third-party call.

### 4.7 Docs

- `docs/inference-endpoints.http` — add the `PUT _inference/rerank/...` request from §1, with the key as a
  variable.
- `README.md` — new row in the modes table; `ELSER_RERANK` in the API `mode` enum; `ELASTIC_RERANK_ID` and
  `JINA_API_KEY` in the configuration table; a note that this mode makes an outbound call to a third party
  (relevant to anyone running this in a restricted network) and that its page depth is capped at `W`.

---

## 5. Tests

Mirroring the existing split — unit tests assert the request *shape*, the IT proves a real cluster
*accepts* it.

### Unit (`MovieRepositoryTest`) — no cluster, no key

```java
@Test void elserRerankWrapsTheSemanticRetrieverInATextSimilarityReranker()
@Test void elserRerankSendsTheQueryAsInferenceTextAndRerankTheePlotField()
@Test void elserRerankUsesTheConfiguredInferenceId()
@Test void elserRerankGrowsTheRankWindowWhenPagingDeeper()
@Test void elserRerankAppliesFiltersToTheInnerRetriever()   // 8.17 client cannot filter at reranker level
@Test void elserRerankFallsBackToBm25WhenAnAttributeSortIsRequested()
```

All of these work with the existing `SearchRequest`-capturing mock — assert via
`captured.retriever().textSimilarityReranker()`.

### `SearchModeTest`, `MovieServiceTest`

Parse `elser_rerank` case-insensitively; endpoint naming; the two new error classifications; page clamping
at `W`.

### Integration (`MovieRepositoryIT`) — the honest version

We **cannot** test Jina in CI: it needs a paid external key and outbound network from the test container.
Two things we *can* do, and the distinction matters:

1. **Shape validation without any model.** Issue the reranked search against the container with an
   inference ID that doesn't exist and assert the failure is
   `resource_not_found_exception` *about the inference endpoint* — not a `parsing_exception` or
   `x_content_parse_exception`. That proves the DSL we generate is structurally valid to a real 8.17
   cluster, which is the part most likely to break on a client upgrade. Cheap, deterministic, no license.
2. **Optional end-to-end**, `@EnabledIfEnvironmentVariable(named = "JINA_API_KEY", ...)`, creating the
   endpoint and asserting the reranked order differs from the ELSER-only order. Skipped by default and
   named so the skip is legible — the same discipline `MovieRepositoryIT` already applies to Docker.

Coverage gate stays at 90 % / 85 %; the new code is all reachable from unit tests.

---

## 6. Suggested sequencing

| Phase | Work | Verification |
|---|---|---|
| 1 | Create the rerank endpoint (§1), confirm with `GET _inference` and a standalone `POST _inference/rerank/...` | Endpoint returns scores for a hand-made query + 3 plots |
| 2 | `SearchMode`, `ElasticsearchProperties`, `application.yml` | `mvn test` — existing 180 pass, new mode parses |
| 3 | `MovieRepository.searchElserRerank` + unit tests | New shape tests pass |
| 4 | `MovieSearchPage` ceiling + `MovieService` clamping/errors | Page-depth and error-classification tests pass |
| 5 | Template copy + README + `.http` | `mvn verify`; manual smoke on `/search?q=…&mode=ELSER_RERANK` |
| 6 | Measure | Compare p95 latency and result quality vs. plain ELSER at `W` = 25/50/100 |

Phase 6 is the one people skip. A reranker that adds 400 ms and reorders nothing is worse than no
reranker, and this project exists to *compare* strategies — so the mode should ship with a number attached.

Rough size: ~120 lines of production code, ~200 of tests, 1 new exception type. No dependency changes.

---

## 7. Decisions I need from you

1. **Model** — `jina-reranker-v3` (assumed) or `jina-reranker-v2-base-multilingual` (faster/cheaper)?
2. **Endpoint source** — your own Jina key (Option C), EIS preconfigured (B), or Elastic's built-in
   reranker (A)? A is the only one that adds no secret and no egress; C is what "ELSER + Jina" implies.
3. **Inner retriever** — bare ELSER (as planned) or wrap the existing RRF(BM25 + ELSER) for genuinely
   hybrid recall?
4. **Mode name** — `ELSER_RERANK` (proposed), or something else? It appears in the public API `mode` enum,
   so it's effectively permanent.
5. **`rank_window_size` default** — 50 (proposed). This is the latency/cost/quality dial and also caps how
   deep users can page in this mode.
6. **Typed DSL vs. raw JSON** — accept losing `min_score` (recommended: keep the typed DSL, since it's what
   keeps user input un-escapable), or hand-build JSON to get it?

---

## Sources

- [Elasticsearch `text_similarity_reranker` retriever reference](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/retrievers/text-similarity-reranker-retriever) — parameters, defaults, GA status, score normalisation
- [Elasticsearch retrievers reference](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/retrievers) — `from`/`size` semantics and the top-level `sort` restriction
- [How to use Jina with Elasticsearch — jina-reranker-v3](https://www.elastic.co/search-labs/tutorials/jina-tutorial/jina-reranker-v3) — endpoint creation and a worked `text_similarity_reranker` query
- [Create a JinaAI inference endpoint (API docs)](https://www.elastic.co/docs/api/doc/elasticsearch/v9/operation/operation-inference-put-jinaai) — `rerank` task type, `top_n`, `return_documents`, API-key immutability
- [jina-rerankers on Elastic Inference Service](https://www.elastic.co/search-labs/blog/jina-rerankers-elastic-inference-service) — preconfigured `.jina-reranker-*` endpoints
- [jina-reranker-v3 model card](https://jina.ai/models/jina-reranker-v3/) — 0.6B, multilingual, 131 k token budget
- [Jina AI & Elastic: open inference API](https://www.elastic.co/search-labs/blog/jina-ai-embeddings-rerank-model-open-inference-api) — v2-base-multilingual characteristics
