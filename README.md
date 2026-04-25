# Elastiflix Java

A movie search web application built with **Spring Boot** and **Elasticsearch**, demonstrating four distinct search strategies side-by-side: classic BM25 keyword search, sparse semantic search with ELSER, dense semantic search with E5, and a hybrid approach using Reciprocal Rank Fusion (RRF).

![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-green)
![Elasticsearch](https://img.shields.io/badge/Elasticsearch-8.17-yellow)
![License](https://img.shields.io/badge/license-Apache%202.0-lightgrey)

---

## Features

- **Four search modes** selectable from the UI

  | Mode | Strategy | Description |
  |------|----------|-------------|
  | BM25 | Keyword | `multi_match` across `title`, `original_title`, `overview`, `plot` with field boosting |
  | Semantic (ELSER) | Sparse vector | `semantic` query on the `plot_elser` field using the `.elser-model-2` |
  | Semantic (E5) | Dense vector | `semantic` query on the `plot_e5` field using `.multilingual-e5-small` |
  | Hybrid | BM25 + ELSER via RRF | `retriever.rrf` combining both standard retrievers (ES 8.14+) |

- **Paginated results** — 50 results per page with a numbered page navigator
- **Movie detail page** — full metadata, poster image, and backdrop via TMDB
- **REST API** — JSON endpoints at `/api/search` and `/api/movies/{id}`
- **Graceful degradation** — inline warning when an inference endpoint is not deployed, no crash
- **Dark-themed UI** built with Thymeleaf and Tailwind CSS (CDN)

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.4.1 |
| Template engine | Thymeleaf 3 |
| CSS | Tailwind CSS (CDN) |
| Search engine | Elasticsearch 8.17 |
| ES Java client | `co.elastic.clients:elasticsearch-java:8.17.0` |
| Build | Maven (single module) |

---

## Prerequisites

- Java 21+
- Maven 3.8+
- A running **Elasticsearch 8.17+** cluster
- The `elastiflix-movies` index populated by [elastiflix-loader-java](../elastiflix-loader-java)

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
