# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build
mvn clean package

# Run locally
mvn spring-boot:run

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=ClassName

# Run the packaged JAR
java -jar target/elastiflix-java-0.0.1-SNAPSHOT.jar
```

**Required environment variables before running:**
```bash
export ELASTIC_HOST=https://localhost:9200   # Full Elasticsearch URL
export ELASTIC_APIKEY=<base64-encoded-key>   # ES API key
```

## Architecture Overview

Spring Boot 3.4.1 / Java 21 web app that demos five Elasticsearch search strategies side-by-side. The app connects to an external Elasticsearch 8.17+ cluster (index: `elastiflix-movies`) populated by a separate loader project.

### Request Flow

```
Browser → [HomeController | SearchController | MovieDetailController]
               ↓
          [MovieService]  (image path enrichment)
               ↓
          [MovieRepository]  (builds ES query DSL)
               ↓
          [ElasticsearchClient]  (co.elastic.clients, API key auth)
               ↓
          Elasticsearch cluster  →  Thymeleaf templates → HTML
```

REST equivalents: `MovieApiController` exposes `/api/search` and `/api/movies/{id}` returning JSON.

### Five Search Modes (`SearchMode` enum)

| Mode | Strategy | ES Query |
|------|----------|----------|
| `TITLE` | Multi-match on title fields | `title`, `original_title` |
| `BM25` | Multi-match with field boosting | title, overview, plot with boosts |
| `ELSER` | Sparse semantic embedding | Semantic query on `plot_elser` field |
| `E5` | Dense semantic embedding | Semantic query on `plot_e5` field |
| `HYBRID` | BM25 + ELSER via RRF | Reciprocal Rank Fusion (ES 8.14+) |

All five query builders live in `MovieRepository`. Hybrid mode silently falls back to BM25 when sorting is applied, because ES's RRF retriever does not support `sort`.

### Key Classes

- **`ElasticsearchConfig`** — builds the `ElasticsearchClient` bean; `elasticsearch.ssl-verify: false` skips certificate validation (dev only)
- **`MovieRepository`** — all five query strategies (`searchTitle`, `searchBm25`, `searchSemantic`, `searchHybrid`, `findById`); accepts a `SearchFilters` record (genre, year, rating) and handles pagination
- **`MovieService`** — thin orchestration layer; prepends TMDB CDN base URL to relative poster/backdrop paths
- **`SearchController`** — handles filter parameters, gracefully catches `IOException` when ELSER/E5 inference endpoints are not deployed
- **`AppProperties`** — binds `elasticsearch.index`, `app.page-size`, `app.tmdb-image-base` from `application.yml`

### Frontend

Thymeleaf 3 templates with Tailwind CSS (CDN). Templates are in `src/main/resources/templates/`; reusable fragments are under `templates/fragments/`. Thymeleaf cache is disabled (`spring.thymeleaf.cache: false`).

## Elasticsearch Setup

The app requires two ML inference endpoints for semantic search modes. Deploy via Kibana Dev Tools or the ES REST API:

```json
// ELSER sparse embedding
PUT _inference/sparse_embedding/elser
{ "service": "elasticsearch", "service_settings": { "model_id": ".elser-model-2", "num_allocations": 1, "num_threads": 1 } }

// E5 dense embedding
PUT _inference/text_embedding/e5
{ "service": "elasticsearch", "service_settings": { "model_id": ".multilingual-e5-small", "num_allocations": 1, "num_threads": 1 } }
```

Endpoint definition JSON files are also stored in `src/main/resources/elastic-cluster/`.

If inference endpoints are missing, `SearchController` catches the resulting `IOException` and renders a helpful error message — the app degrades gracefully to BM25.
