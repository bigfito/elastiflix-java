# Elastiflix Java

A movie search web application built with **Spring Boot** and **Elasticsearch**, demonstrating six distinct search strategies side-by-side: standard title search, classic BM25 keyword search, sparse semantic search with ELSER, dense semantic search with E5, a hybrid approach using Reciprocal Rank Fusion (RRF), and a two-stage ELSER retrieval reranked by a cross-encoder.

![Java](https://img.shields.io/badge/Java-25-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-green)
![Elasticsearch](https://img.shields.io/badge/Elasticsearch-9.5-yellow)
![License](https://img.shields.io/badge/license-Apache%202.0-lightgrey)

---

## Features

- **Six search modes** selectable from the UI

  | Mode | Strategy | Description |
  |------|----------|-------------|
  | `TITLE` | Standard | `multi_match` on `title` and `original_title` (Default) |
  | `BM25` | Keyword | `multi_match` across `title`, `original_title`, `overview`, `plot` with field boosting |
  | `ELSER` | Sparse vector | `semantic` query on the `plot_elser` field using `.elser-model-2` |
  | `E5` | Dense vector | `semantic` query on the `plot_e5` field using `.multilingual-e5-small` |
  | `HYBRID` | BM25 + ELSER via RRF | `retriever.rrf` combining both standard retrievers (ES 8.14+) |
  | `ELSER_JINA` | ELSER + cross-encoder rerank | `retriever.text_similarity_reranker` rescoring ELSER's candidates against the query. Highest quality, highest latency — and the only mode whose paging is capped by `rank_window_size` rather than the result window |

- **Filtering & Sorting** — filter by genre, release year or rating; sort by RATING (high to low) or YEAR (newest first).
  Genres are picked from a closed list rather than typed free-hand, because the filter is an unanalyzed `terms`
  query: `action` would not match the indexed `Action`. Values arriving through the REST API are canonicalised
  the same way.
- **Honest Hybrid fallback** — RRF cannot combine relevance ranking with an attribute sort, so choosing a sort in
  Hybrid mode runs BM25 instead. The UI says so inline, and the API reports it as `effectiveMode`.
- **Flexible results view** — toggle between **Grid** and **List**.
- **Dynamic pagination** — 25, 50 or 100 results per page. Paging stops at Elasticsearch's
  `index.max_result_window` (10 000 documents) — or, in `ELSER_JINA`, at `elasticsearch.rerank.window-size`,
  since a document the reranker never scored can never be returned. Either way the UI says how far you can
  actually reach instead of offering pages that would silently serve different results.
- **Movie detail page** — full metadata, poster image and backdrop via TMDB.
- **REST API** — JSON endpoints at `/api/search` and `/api/movies/{id}`.
- **Graceful degradation** — an inline warning when an inference endpoint is not deployed; no crash, no error page.
- **Health endpoint** — `/actuator/health` reports DOWN if the cluster is unreachable *or* the index is missing.
- **Modern sidebar UI** — filters and search options on the left, results on the right. Thymeleaf and Tailwind CSS.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 25 |
| Framework | Spring Boot 4.1.0 (Spring Framework 7.0.8) |
| Template engine | Thymeleaf 3.1 |
| CSS | Hand-written stylesheet, self-hosted (no CDN, no Node toolchain) |
| Search engine | Elasticsearch 9.5 |
| ES Java client | `co.elastic.clients:elasticsearch-java:9.5.0` |
| Build | Maven (single module, wrapper included) |
| Tests | JUnit 6, Mockito, AssertJ, Testcontainers, JaCoCo |

> **Note on Jackson.** Spring Boot 4 serializes HTTP with Jackson 3 (`tools.jackson`), while the Elasticsearch
> client's `JacksonJsonpMapper` needs Jackson 2. The explicit `jackson-databind` dependency in `pom.xml` is
> therefore required, not redundant — removing it fails at runtime with `NoClassDefFoundError`. `Movie` maps its
> snake_case fields with per-field `@JsonProperty` for the same reason: a class-level `@JsonNaming` strategy would
> be honoured by only one of the two mappers.

---

## Prerequisites

- **Java 25+** (the build targets 25; it will not compile on 21)
- **Maven 3.8+**, or just use the bundled `./mvnw` wrapper
- A running **Elasticsearch 9.5+** (or 8.19+) cluster
- The `elastiflix-movies` index, populated by the companion Elastiflix loader project
- **Docker** — only for the integration test (`mvn verify`); not needed to build or run the app

---

## Configuration

Credentials come from the environment. **Never hardcode them in `application.yml` or in an IDE run
configuration** — `.run/` is git-ignored precisely because run configurations tend to accumulate real API keys.

| Variable | Required | Default | Description |
|----------|:--------:|---------|-------------|
| `ELASTIC_HOST` | ✅ | — | Cluster URL, e.g. `https://my-cluster.es.europe-west1.gcp.cloud.es.io:443`. Scheme and host required; a path is rejected at startup |
| `ELASTIC_APIKEY` | ✅ | — | Base64-encoded API key, sent as `Authorization: ApiKey …` |
| `ELASTIC_SSL_VERIFY` | — | `true` | Set `false` **only** for a local cluster with a self-signed certificate |
| `ELASTIC_RERANK_ID` | — | `eis-jina-reranker` | Inference endpoint (task type `rerank`) used by `ELSER_JINA`. The default is an Elastic Inference Service endpoint running `jina-reranker-v3.5` — create it once (see below); EIS holds the provider key, so none is needed here. Alternatives: the preconfigured `.jina-reranker-v3.5`, or `.rerank-v1-elasticsearch` for Elastic's in-cluster reranker |
| `ELASTIC_RERANK_WINDOW` | — | `50` | Candidates ELSER hands to the reranker. Also caps how deep `ELSER_JINA` can page. `jina-reranker-v3.5` is listwise, sharing one **131 k-token** context across query + candidates, so the bound is that token budget rather than a document count — raising this mainly costs latency. If you point `ELASTIC_RERANK_ID` at a **v3** endpoint, keep it at **64 or below** |

Both required variables are validated at startup, so a missing one fails immediately with a message naming the
property rather than an NPE on the first search.

`ELASTIC_SSL_VERIFY=false` disables TLS certificate *and* hostname validation. Because that would expose the API
key to anyone able to intercept the connection, the application **refuses to start** if it is disabled for a
non-local HTTPS host. Loopback addresses, RFC-1918 ranges, single-label hostnames (`elasticsearch` in
docker-compose) and `.local`/`.internal`/`.test` suffixes are accepted; a public FQDN is not.

Non-secret settings live in `src/main/resources/application.yml`:

```yaml
elasticsearch:
  index: elastiflix-movies
  connect-timeout: 5s
  socket-timeout: 30s
  rerank:
    inference-id: ${ELASTIC_RERANK_ID:eis-jina-reranker}    # EIS endpoint running jina-reranker-v3.5
    window-size: ${ELASTIC_RERANK_WINDOW:50}               # candidates reranked, and the paging cap

app:
  page-size: 50                                              # the one authority for the default page size
  tmdb-image-base: https://image.tmdb.org/t/p/w500
  tmdb-image-base-large: https://image.tmdb.org/t/p/w1280
```

---

## Build & Run

```bash
# 1. Point the app at your cluster
export ELASTIC_HOST="https://my-cluster.es.europe-west1.gcp.cloud.es.io:443"
export ELASTIC_APIKEY="<your-base64-encoded-api-key>"

# 2. Run it
./mvnw spring-boot:run
```

Then open <http://localhost:8080>.

```bash
# Development mode — disables Thymeleaf template caching
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Build an executable jar
./mvnw clean package
java -jar target/elastiflix-java-0.0.1-SNAPSHOT.jar

# Health check (reports DOWN if the cluster is unreachable or the index is absent)
curl -s localhost:8080/actuator/health | jq
```

At startup the application pings the cluster and checks that the index exists, logging an actionable error if
either fails. It deliberately still starts: the UI degrades gracefully rather than refusing to boot.

---

## Required inference endpoints

TITLE and BM25 work with no inference endpoint at all. Semantic and Hybrid modes need these two — create them
once per cluster in Kibana → Dev Tools, or run [`docs/inference-endpoints.http`](docs/inference-endpoints.http)
from your IDE.

```http
PUT _inference/sparse_embedding/elser
{
  "service": "elasticsearch",
  "service_settings": {
    "model_id": ".elser-model-2",
    "num_allocations": 1,
    "num_threads": 1
  }
}
```

```http
PUT _inference/text_embedding/e5
{
  "service": "elasticsearch",
  "service_settings": {
    "model_id": ".multilingual-e5-small",
    "num_allocations": 1,
    "num_threads": 1
  }
}
```

A reranked search additionally needs an endpoint with the `rerank` task type. The default is an
**Elastic Inference Service** endpoint running `jina-reranker-v3.5` — a 0.6B multilingual listwise model
and a drop-in upgrade to v3. `service: elastic` means EIS holds the Jina credentials, so no provider key
appears in this request or in the application:

```http
PUT _inference/rerank/eis-jina-reranker
{
  "service": "elastic",
  "service_settings": {
    "model_id": "jina-reranker-v3.5"
  }
}
```

Two alternatives, both selected with `ELASTIC_RERANK_ID` and needing no code change:

- **`.jina-reranker-v3.5`** — preconfigured on EIS, nothing to create.
- **`.rerank-v1-elasticsearch`** — Elastic's own cross-encoder, runs in-cluster with no external service:

```http
PUT _inference/rerank/.rerank-v1-elasticsearch
{
  "service": "elasticsearch",
  "service_settings": {
    "model_id": ".rerank-v1",
    "num_allocations": 1,
    "num_threads": 1
  }
}
```

| Endpoint ID | Type | Required for |
|-------------|------|-------------|
| `elser` | `sparse_embedding` | Semantic (ELSER), Hybrid, ELSER + Jina rerank |
| `e5` | `text_embedding` | Semantic (E5) |
| `eis-jina-reranker` (or `ELASTIC_RERANK_ID`) | `rerank` | ELSER + Jina rerank |

> **Network note.** `ELSER_JINA` is the only mode that depends on something outside your cluster: the
> reranking call goes from Elasticsearch out to the Elastic Inference Service, which in turn calls Jina.
> The application itself makes no third-party call and holds no Jina credential — but in a restricted or
> air-gapped network the mode will fail where the other five keep working. Point `ELASTIC_RERANK_ID` at
> `.rerank-v1-elasticsearch` to rerank entirely in-cluster with no egress.

If an endpoint is missing, the mode that needs it shows an inline warning naming the endpoint and suggesting
BM25 instead. Nothing else breaks.

---

## Index expectations

The app reads, but never writes, the `elastiflix-movies` index. Field types that matter to it:

| Field | Type | Used by |
|-------|------|---------|
| `id` | `keyword` | `/movies/{id}` lookup (the domain id, not `_id`) |
| `title`, `original_title` | `text` | TITLE, BM25 (boosted `^3` / `^2`) |
| `overview`, `plot` | `text` | BM25 |
| `genres` | `keyword` | Genre filter — **must** be `keyword`, or the `terms` filter never matches |
| `rating` | `keyword` | Rating filter |
| `release_date` | `date` | Year filter, YEAR sort |
| `vote_average` | `double` | RATING sort |
| `plot_elser` | `semantic_text` (via `elser`) | Semantic (ELSER), Hybrid |
| `plot_e5` | `semantic_text` (via `e5`) | Semantic (E5) |

Remaining metadata (`cast`, `runtime`, `budget`, `revenue`, `production_companies`, …) is displayed on the detail
page but not queried. Unknown fields are ignored, so extra ones are harmless.

---

## REST API

### `GET /api/search`

| Param | Type | Default | Notes |
|-------|------|---------|-------|
| `q` | string | — | **Required**, non-blank; a blank value returns 400 |
| `mode` | enum | `TITLE` | `TITLE`, `BM25`, `ELSER`, `E5`, `HYBRID`, `ELSER_JINA`; unknown values fall back to `TITLE` |
| `page` | int | `1` | Clamped so `from + size` stays within the reachable window — 10 000 documents, or `elasticsearch.rerank.window-size` in `ELSER_JINA` |
| `size` | int | `app.page-size` | Clamped to 1–100; out-of-range falls back to the configured default |
| `genres` | list | — | Repeatable or comma-separated; canonicalised to the indexed casing |
| `year` | int | — | Ignored unless plausible (1870 … current year + 5) |
| `rating` | string | — | `G`, `PG`, `PG-13`, `R`, `NC-17` |
| `sort` | enum | relevance | `RATING` or `YEAR` |

```bash
curl -s "localhost:8080/api/search?q=batman&mode=HYBRID&genres=action&size=5" | jq
```

```json
{
  "movies": [ { "id": "155", "title": "The Dark Knight", "vote_average": 8.5 } ],
  "totalHits": 2,
  "currentPage": 1,
  "pageSize": 5,
  "totalPages": 1,
  "query": "batman",
  "mode": "HYBRID",
  "effectiveMode": "HYBRID",
  "windowLimited": false
}
```

- `mode` is what you asked for; **`effectiveMode`** is what actually ran. They differ when Hybrid degrades to
  BM25 because a sort was requested.
- **`windowLimited`** is `true` when more documents matched than the result window can page through, so
  `totalPages` stops short of `totalHits / pageSize`.

### `GET /api/movies/{id}`

Returns the full movie document, or 404. Errors on both endpoints are RFC 7807 `ProblemDetail` JSON carrying a
stable `errorCode` — never an HTML error page.

| `errorCode` | Meaning |
|-------------|---------|
| `SEARCH_UNAVAILABLE` | The cluster is unreachable, or returned an error with no more specific cause |
| `INFERENCE_ENDPOINT_MISSING` | The mode needs an inference endpoint that is not deployed in the cluster |
| `RERANK_UNAVAILABLE` | The rerank endpoint exists but the provider refused the request — rejected key, exhausted quota or rate limit |

---

## Testing

```bash
./mvnw test      # 273 unit tests + JaCoCo coverage gate
./mvnw verify    # also runs MovieRepositoryIT against a real Elasticsearch (needs Docker)

# Fail instead of skipping when Docker is missing (use in CI)
./mvnw verify -Delastiflix.it.requireDocker=true
```

Both run in CI on every push and pull request (`.github/workflows/build.yml`), together with a `gitleaks`
scan over the full history.

The suite is self-contained: `src/test/resources/application.properties` supplies dummy cluster settings, so
`mvn test` needs neither `ELASTIC_*` variables nor a running cluster.

- **Unit tests** mock the Elasticsearch client and assert the shape of every request the repository builds —
  field boosts, filter clauses, sort, pagination offsets and the RRF retriever's `rank_window_size`.
- **`MovieRepositoryIT`** (16 tests) starts Elasticsearch 9.5 in a container and runs TITLE, BM25, all three
  filters, both sorts, pagination and `findById` against it, proving a real cluster accepts those queries. It
  **skips itself when Docker is unavailable** — and names the reason, since a silently skipped integration test
  looks exactly like a passing one. Pass `-Delastiflix.it.requireDocker=true` to make that a failure instead.
  Semantic and Hybrid modes are out of scope there, since they need deployed ML endpoints.
- **Coverage** is enforced by JaCoCo at **90% instruction / 85% branch** (currently 98.6% / 92.4%), bound to the
  `test` phase so a plain `mvn test` fails on a regression instead of deferring it to `verify`. `lombok.config`
  marks generated accessors so they neither count toward nor dilute the figure. Only `ElastiflixApplication` is
  excluded. Report: `target/site/jacoco/index.html`.

---

## Security notes

Because the stylesheet and the single behaviour script are both served from `/static`, the policy needs no
`'unsafe-inline'` and no third-party origin: `script-src 'self'; style-src 'self'`. Page behaviour that used to
live in inline `on*` attributes is wired from `data-*` attributes in `static/js/elastiflix.js`.

The app applies a baseline `Content-Security-Policy` plus `X-Content-Type-Options`, `Referrer-Policy`,
`X-Frame-Options` and `Permissions-Policy` to every response, adds `Strict-Transport-Security` when the request
arrives over HTTPS (never on plain HTTP, which would pin a developer's `localhost` to `https://` for a year),
restricts images to HTTPS, and strips any movie `homepage` that is not `http(s)` so a `javascript:` URL in the
index cannot become a clickable link.

`/actuator/health` runs with `show-details: when-authorized`, so an anonymous caller sees only `UP`/`DOWN`;
the `dev` profile opens it up for local debugging. The health payload never reports the cluster host.

**It still has no authentication, authorization or rate limiting.** Every endpoint is world-readable to anyone
who can reach the port. This is a demo — put it behind authentication and add rate limiting before exposing it
anywhere untrusted.

---

## Known limitations

- **Styling is a hand-written subset of Tailwind, not Tailwind itself.** `src/main/resources/static/css/elastiflix.css`
  defines only the ~205 utility classes the templates actually use, following Tailwind v3 names and scales. This
  removed the `cdn.tailwindcss.com` development build — which compiled classes on every page load, could not be
  pinned with Subresource Integrity, and left the page unstyled offline — without adding a Node toolchain to a
  pure-Maven build. The trade-off: **a Tailwind class that is not in that file silently does nothing.**
  `TemplateAssetsTest` fails the build if a template uses an undefined class, so the failure is loud rather than
  visual. Add new classes to the stylesheet as you use them.
- **Deep paging stops at 10 000 documents** (`index.max_result_window`). The UI says so; `search_after` would be
  the fix if deeper paging were ever needed.
- **`ELSER_JINA` stops far sooner — at `elasticsearch.rerank.window-size` (50 by default).** The reranker only
  scores that many candidates, so nothing beyond them can be returned at any page. The window is deliberately
  *not* widened for deep pages: the model is listwise, so growing the window per request would change the
  candidate set — and therefore the ranking — between one page and the next. The service clamps the page
  instead and the UI says so.
- **Hybrid and reranked modes cannot sort.** RRF fuses ranks and a reranker rescores them, so an attribute sort
  is meaningless — the request runs as BM25 and both the UI and the API report it via `effectiveMode`.

---

## Building a Gen AI Agent

To build a Gen AI agent on top of this project, use the following to define its behaviour and tool usage.

### System role & persona

> You are the **Elastiflix Movie Assistant**, an expert in films and a helpful guide for users exploring the
> Elastiflix database. Your goal is to help users find movies matching their interests, moods or specific
> queries using the project's search capabilities.

### Tooling & API usage

1. **Search movies (`GET /api/search`)**
   - **Params**: `q`, `mode` (`TITLE`, `BM25`, `ELSER`, `E5`, `HYBRID`, `ELSER_JINA`), `genres`, `year`,
     `rating`, `sort`, `page`, `size`.
   - **Usage**: `mode=HYBRID` for natural-language questions, `mode=TITLE` for a specific movie name,
     `mode=ELSER_JINA` when precision matters more than latency — but do not page past
     `elasticsearch.rerank.window-size` results in that mode, because nothing beyond it was ever scored.
   - Check **`effectiveMode`** in the response: if it differs from the mode you requested, the search degraded
     (you asked for Hybrid *and* a sort) and you should drop `sort` to get true hybrid ranking.
   - Pass genres in any casing — they are canonicalised server-side.

2. **Get movie details (`GET /api/movies/{id}`)**
   - Retrieve full metadata (plot, cast, budget) for one movie.

### Guidelines for recommendations

- **Context awareness**: prefer `HYBRID` for recommendations — best semantic results.
- **Explaining results**: use `tagline` and `overview` to explain *why* a movie matches.
- **Handle degradation**: a `503` with `errorCode: INFERENCE_ENDPOINT_MISSING` means the cluster has no
  ELSER/E5/rerank endpoint deployed. Retry with `mode=BM25` rather than reporting a failure — or with
  `mode=ELSER` if the mode you tried was `ELSER_JINA`, which keeps the semantic recall.
  `RERANK_UNAVAILABLE` means the reranker's provider refused the request; retry with `mode=ELSER`.
- **Search mode cheat sheet**:
  - `TITLE` — exact/partial title match
  - `BM25` — keyword search
  - `ELSER` / `E5` — semantic/vector search
  - `HYBRID` — best of both worlds; the default choice for recommendations
  - `ELSER_JINA` — ELSER candidates rescored by a cross-encoder: the most precise, the slowest, and limited
    to the first `rank_window_size` results

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
