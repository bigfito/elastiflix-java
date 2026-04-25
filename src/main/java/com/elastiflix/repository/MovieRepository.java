package com.elastiflix.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.elastiflix.config.AppProperties;
import com.elastiflix.model.Movie;
import com.elastiflix.model.SearchMode;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Optional;

@Repository
public class MovieRepository {

    private final ElasticsearchClient esClient;
    private final AppProperties props;

    public MovieRepository(ElasticsearchClient esClient, AppProperties props) {
        this.esClient = esClient;
        this.props = props;
    }

    public SearchResult search(String queryText, SearchMode mode, int page) throws IOException {
        int from = (page - 1) * props.pageSize;

        return switch (mode) {
            case BM25   -> searchBm25(queryText, from);
            case ELSER  -> searchSemantic(queryText, "plot_elser", from);
            case E5     -> searchSemantic(queryText, "plot_e5", from);
            case HYBRID -> searchHybrid(queryText, from);
        };
    }

    private SearchResult searchBm25(String queryText, int from) throws IOException {
        SearchResponse<Movie> response = esClient.search(s -> s
                .index(props.esIndex)
                .from(from)
                .size(props.pageSize)
                .query(q -> q
                        .multiMatch(mm -> mm
                                .query(queryText)
                                .fields(List.of("title^3", "original_title^2", "overview", "plot"))
                        )
                ),
                Movie.class
        );
        return toSearchResult(response);
    }

    private SearchResult searchSemantic(String queryText, String field, int from) throws IOException {
        String escaped = queryText.replace("\\", "\\\\").replace("\"", "\\\"");
        String body = """
                {
                  "from": %d,
                  "size": %d,
                  "query": {
                    "semantic": {
                      "field": "%s",
                      "query": "%s"
                    }
                  }
                }
                """.formatted(from, props.pageSize, field, escaped);

        SearchResponse<Movie> response = esClient.search(
                s -> s.index(props.esIndex).withJson(new StringReader(body)),
                Movie.class
        );
        return toSearchResult(response);
    }

    private SearchResult searchHybrid(String queryText, int from) throws IOException {
        // Hybrid BM25 + ELSER using the retriever/rrf API (ES 8.14+)
        String escaped = queryText.replace("\\", "\\\\").replace("\"", "\\\"");
        String body = """
                {
                  "from": %d,
                  "size": %d,
                  "retriever": {
                    "rrf": {
                      "retrievers": [
                        {
                          "standard": {
                            "query": {
                              "multi_match": {
                                "query": "%s",
                                "fields": ["title^3", "original_title^2", "overview", "plot"]
                              }
                            }
                          }
                        },
                        {
                          "standard": {
                            "query": {
                              "semantic": {
                                "field": "plot_elser",
                                "query": "%s"
                              }
                            }
                          }
                        }
                      ],
                      "rank_window_size": 100,
                      "rank_constant": 60
                    }
                  }
                }
                """.formatted(from, props.pageSize, escaped, escaped);

        SearchResponse<Movie> response = esClient.search(
                s -> s.index(props.esIndex).withJson(new StringReader(body)),
                Movie.class
        );
        return toSearchResult(response);
    }

    public Optional<Movie> findById(String id) throws IOException {
        // Use term query on the keyword 'id' field since _id may differ
        SearchResponse<Movie> response = esClient.search(s -> s
                .index(props.esIndex)
                .size(1)
                .query(q -> q
                        .term(t -> t
                                .field("id")
                                .value(id)
                        )
                ),
                Movie.class
        );

        return response.hits().hits().stream()
                .findFirst()
                .map(Hit::source);
    }

    private SearchResult toSearchResult(SearchResponse<Movie> response) {
        List<Movie> movies = response.hits().hits().stream()
                .map(Hit::source)
                .toList();
        long total = response.hits().total() != null
                ? response.hits().total().value()
                : 0L;
        return new SearchResult(movies, total);
    }

    public record SearchResult(List<Movie> movies, long totalHits) {}
}
