# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build (unit tests + integration tests; the IT needs Docker, otherwise it self-skips)
./mvnw verify

# Run locally
./mvnw spring-boot:run

# Run unit tests only
./mvnw test

# Run a single test class
./mvnw test -Dtest=ClassName

# Run the packaged JAR
java -jar target/elastiflix-java-0.0.1-SNAPSHOT.jar
```

**Required environment variables before running:**
```bash
export ELASTIC_HOST=https://localhost:9200   # Full Elasticsearch URL (scheme required)
export ELASTIC_APIKEY=<base64-encoded-key>   # ES API key
```

Optional: `ELASTIC_SSL_VERIFY` (defaults to `true`; `false` is rejected at startup for non-local HTTPS hosts), `ELASTIC_RERANK_ID` (defaults to `eis-jina-reranker`), `ELASTIC_RERANK_WINDOW` (defaults to `50`).

## Architecture Overview

Spring Boot 4.1.0 / Java 25 web app that demos six Elasticsearch search strategies side-by-side. The app connects to an external Elasticsearch 9.x cluster (index: `elastiflix-movies`) populated by a separate loader project. `ElasticsearchStartupCheck` verifies the connection at boot and logs an actionable error if it fails; `ElasticsearchHealthIndicator` reports it under `/actuator/health`.

### Request Flow

```
Browser → [HomeController | SearchController | MovieDetailController]
               ↓
          [MovieService]  (image path enrichment, error classification)
               ↓
          [MovieRepository]  (builds ES query DSL)
               ↓
          [ElasticsearchClient]  (co.elastic.clients 9.x, API key auth)
               ↓
          Elasticsearch cluster  →  Thymeleaf templates → HTML
```

REST equivalents: `MovieApiController` exposes `/api/search` and `/api/movies/{id}` returning JSON, with errors handled by `ApiExceptionHandler` (web pages use `GlobalExceptionHandler`).

### Six Search Modes (`SearchMode` enum)

| Mode | Strategy | ES Query |
|------|----------|----------|
| `TITLE` | Multi-match on title fields | `title`, `original_title` |
| `BM25` | Multi-match with field boosting | title, overview, plot with boosts |
| `ELSER` | Sparse semantic embedding | Semantic query on `plot_elser` field |
| `E5` | Dense semantic embedding | Semantic query on `plot_e5` field |
| `HYBRID` | BM25 + ELSER via RRF | Reciprocal Rank Fusion retriever |
| `ELSER_JINA` | ELSER retriever + Jina reranker | `text_similarity_reranker` retriever over a rerank inference endpoint |

All query builders live in `MovieRepository`. `HYBRID` and `ELSER_JINA` fall back to BM25 when a sort is applied (relevance reranking is meaningless under an explicit sort); the fallback is surfaced as `effectiveMode` so the UI can say so. `ELSER_JINA` reranks the top `window-size` candidates (default 50), which also caps how deep that mode can page.

### Key Classes

- **`ElasticsearchConfig`** — builds the `ElasticsearchClient` bean from `ElasticsearchProperties`
- **`ElasticsearchProperties` / `RerankProperties` / `AppProperties`** — immutable, validated `@ConfigurationProperties` records binding `elasticsearch.*`, `elasticsearch.rerank.*` and `app.*` from `application.yml`; missing required values fail fast at startup
- **`MovieRepository`** — all query strategies (`searchTitle`, `searchBm25`, `searchSemantic`, `searchHybrid`, `searchElserJina`, `findById`); accepts a `SearchFilters` record (genres, year range) and handles pagination
- **`MovieService`** — orchestration layer; prepends TMDB CDN base URL to poster/backdrop paths and classifies Elasticsearch failures into the `ElastiflixException` hierarchy via `ElasticsearchErrors`
- **`SearchController` / `MovieSearchParams`** — bind and validate search parameters (query, mode, page, size, genre/year filters, sort, view)
- **`MovieGenre` / `ReleaseYear`** — closed enum sets backing the genre and release-year filters; incoming values are canonicalised before reaching the terms query
- **`MovieSearchPage`** — pagination/result model returned to templates and the JSON API
- **Exceptions** — `ElastiflixException` hierarchy with error codes (`InferenceEndpointMissingException`, `RerankUnavailableException`, `SearchUnavailableException`, ...)

### Frontend

Thymeleaf templates with Tailwind CSS plus project assets in `src/main/resources/static/` (`css/elastiflix.css`, `js/elastiflix.js`). Templates are in `src/main/resources/templates/`; reusable fragments under `templates/fragments/`. Template caching is disabled only in the `dev` profile (`-Dspring.profiles.active=dev`).

### Tests

- Unit tests (`./mvnw test`) cover controllers, service, repository query DSL, config binding, and exception mapping — no cluster needed.
- `MovieRepositoryIT` (failsafe, runs during `./mvnw verify`) starts a real Elasticsearch container via Testcontainers with a self-generated **trial** license (the basic license rejects `text_similarity_reranker`). It skips itself when Docker is unavailable; CI can force it with `-Delastiflix.it.requireDocker=true`.

## Elasticsearch Setup

The app requires ML inference endpoints for the semantic modes. Ready-to-run requests live in `docs/inference-endpoints.http`:

```json
// ELSER sparse embedding
PUT _inference/sparse_embedding/elser
{ "service": "elasticsearch", "service_settings": { "model_id": ".elser-model-2", "num_allocations": 1, "num_threads": 1 } }

// E5 dense embedding
PUT _inference/text_embedding/e5
{ "service": "elasticsearch", "service_settings": { "model_id": ".multilingual-e5-small", "num_allocations": 1, "num_threads": 1 } }

// Jina reranker (Elastic Inference Service manages the provider key)
PUT _inference/rerank/eis-jina-reranker
{ "service": "elastic", "service_settings": { "model_id": "jina-reranker-v3.5" } }
```

If an inference endpoint is missing, the error is classified as `InferenceEndpointMissingException` and the UI names the endpoint to deploy instead of a generic failure. Design notes for the rerank mode are in `docs/plan-elser-jina-rerank.md`.
