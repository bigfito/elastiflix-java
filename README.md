# Elastiflix Java

A movie search web application built with **Spring Boot** and **Elasticsearch**, demonstrating five distinct search strategies side-by-side: standard title search, classic BM25 keyword search, sparse semantic search with ELSER, dense semantic search with E5, and a hybrid approach using Reciprocal Rank Fusion (RRF).

![Java](https://img.shields.io/badge/Java-25-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-green)
![Elasticsearch](https://img.shields.io/badge/Elasticsearch-9.x-yellow)
![License](https://img.shields.io/badge/license-Apache%202.0-lightgrey)

---

## Features

- **Five search modes** selectable from the UI

  | Mode | Strategy | Description |
  |------|----------|-------------|
  | TITLE | Standard | `multi_match` on `title` and `original_title` (Default) |
  | BM25 | Keyword | `multi_match` across `title`, `original_title`, `overview`, `plot` with field boosting |
  | Semantic (ELSER) | Sparse vector | `semantic` query on the `plot_elser` field using the `.elser-model-2` |
  | Semantic (E5) | Dense vector | `semantic` query on the `plot_e5` field using `.multilingual-e5-small` |
  | Hybrid | BM25 + ELSER via RRF | `retriever.rrf` combining both standard retrievers (ES 8.14+) |

- **Faceted Filtering** — **Genre** and **Release Year** dropdowns are populated dynamically from the current search resultset via Elasticsearch aggregations (`terms` on `genres`, `date_histogram` on `release_date`). A `post_filter` keeps the available options stable as the user switches between values, so you can pivot between facets in a single click. **Rating** is a static dropdown (G/PG/PG-13/R/NC-17).
- **Sorting** — Sort results by RATING (High to Low) or YEAR (Newest first).
- **Flexible Results View** — Toggle between **Grid** and **List** views in the search results page.
- **Dynamic Pagination** — Choose between 25, 50, or 100 results per page.
- **Movie detail page** — full metadata, poster image, and backdrop via TMDB
- **REST API** — JSON endpoints at `/api/search` and `/api/movies/{id}`
- **Graceful degradation** — inline warning when an inference endpoint is not deployed, no crash
- **Modern Sidebar UI** — Clean layout with filters and search options on the left, results on the right. Built with Thymeleaf and Tailwind CSS, with a tech-stack logo strip in the footer.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 25 |
| Framework | Spring Boot 4.0.6 (Spring Framework 7) |
| Template engine | Thymeleaf 3 |
| CSS | Tailwind CSS (CDN) |
| Search engine | Elasticsearch 9.x |
| ES Java client | `co.elastic.clients:elasticsearch-java:9.1.0` |
| Build | Maven (single module) |

---

## Prerequisites

- Java 25+
- Maven 3.9.6+
- A running **Elasticsearch 9.x** cluster
- The `elastiflix-movies` index populated by [elastiflix-loader-java](../elastiflix-loader-java) — note that for the dynamic facets to work, `genres` must be a `keyword` (or have a `keyword` subfield) and `release_date` must be a `date` field

### Required inference endpoints

BM25 works without any inference endpoint. For semantic and hybrid modes, deploy these two endpoints in Kibana Dev Tools or the Elasticsearch API:

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

| Endpoint ID | Type | Required for |
|-------------|------|-------------|
| `elser` | `sparse_embedding` | Semantic (ELSER), Hybrid |
| `e5` | `text_embedding` | Semantic (E5) |

---

## Configuration

Edit `src/main/resources/application.yml`:

```yaml
elasticsearch:
  host: https://localhost:9200
  api-key: <your-base64-encoded-api-key>
  index: elastiflix-movies
  ssl-verify: false        # set true in production with a valid certificate

app:
  page-size: 50
  tmdb-image-base: https://image.tmdb.org/t/p/w500
```

The `host` and `api-key` are typically supplied via the `ELASTIC_HOST` and `ELASTIC_APIKEY` environment variables (the defaults in `application.yml` reference them as `${ELASTIC_HOST}` / `${ELASTIC_APIKEY}`).

---

## Build & Run

```bash
# Compile and package
mvn clean package

# Run locally (binds :8080)
ELASTIC_HOST=https://localhost:9200 \
ELASTIC_APIKEY=<base64-api-key> \
mvn spring-boot:run

# Or run the packaged jar
java -jar target/elastiflix-java-0.0.1-SNAPSHOT.jar
```

Open `http://localhost:8080` to land on the home page, or jump straight to `/search?q=matrix`.
