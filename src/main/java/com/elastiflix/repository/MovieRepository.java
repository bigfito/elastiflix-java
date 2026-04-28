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

    public SearchResult search(String queryText, SearchMode mode, int page, int size, SearchFilters filters, String sort) throws IOException {
        int from = (page - 1) * size;

        return switch (mode) {
            case TITLE  -> searchTitle(queryText, from, size, filters, sort);
            case BM25   -> searchBm25(queryText, from, size, filters, sort);
            case ELSER  -> searchSemantic(queryText, "plot_elser", from, size, filters, sort);
            case E5     -> searchSemantic(queryText, "plot_e5", from, size, filters, sort);
            case HYBRID -> searchHybrid(queryText, from, size, filters, sort);
        };
    }

    private SearchResult searchTitle(String queryText, int from, int size, SearchFilters filters, String sort) throws IOException {
        SearchResponse<Movie> response = esClient.search(s -> s
                        .index(props.esIndex)
                        .from(from)
                        .size(size)
                        .query(q -> q
                                .bool(b -> b
                                        .must(m -> m
                                                .multiMatch(mm -> mm
                                                        .query(queryText)
                                                        .fields(List.of("title", "original_title"))
                                                )
                                        )
                                        .filter(buildFilters(filters))
                                )
                        )
                        .sort(buildSort(sort)),
                Movie.class
        );
        return toSearchResult(response);
    }

    private SearchResult searchBm25(String queryText, int from, int size, SearchFilters filters, String sort) throws IOException {
        SearchResponse<Movie> response = esClient.search(s -> s
                .index(props.esIndex)
                .from(from)
                .size(size)
                .query(q -> q
                        .bool(b -> b
                                .must(m -> m
                                        .multiMatch(mm -> mm
                                                .query(queryText)
                                                .fields(List.of("title^3", "original_title^2", "overview", "plot"))
                                        )
                                )
                                .filter(buildFilters(filters))
                        )
                )
                .sort(buildSort(sort)),
                Movie.class
        );
        return toSearchResult(response);
    }

    private SearchResult searchSemantic(String queryText, String field, int from, int size, SearchFilters filters, String sort) throws IOException {
        String escaped = queryText.replace("\\", "\\\\").replace("\"", "\\\"");

        // Semantic query is part of the query object.
        // If we have filters, we need a bool query with must: semantic and filter: buildFilters
        SearchResponse<Movie> response = esClient.search(s -> s
                .index(props.esIndex)
                .from(from)
                .size(size)
                .query(q -> q
                        .bool(b -> b
                                .must(must -> must
                                        .withJson(new StringReader("""
                                                {
                                                  "semantic": {
                                                    "field": "%s",
                                                    "query": "%s"
                                                  }
                                                }
                                                """.formatted(field, escaped)))
                                )
                                .filter(buildFilters(filters))
                        )
                )
                .sort(buildSort(sort)),
                Movie.class
        );
        return toSearchResult(response);
    }

    private SearchResult searchHybrid(String queryText, int from, int size, SearchFilters filters, String sort) throws IOException {
        // Hybrid BM25 + ELSER using the retriever/rrf API (ES 8.14+)
        String escaped = queryText.replace("\\", "\\\\").replace("\"", "\\\"");

        // If filters are present, we need to apply them to both retrievers
        List<co.elastic.clients.elasticsearch._types.query_dsl.Query> esFilters = buildFilters(filters);

        // Re-evaluating searchHybrid to include filters.
        // It's probably easier to build the full JSON if we have filters.

        // RRF doesn't support 'sort' parameter at the top level in the same way standard search does
        // because it combines results from multiple retrievers using RRF algorithm.
        // If 'sort' is requested, we might have to fallback or handle it differently.
        // However, if the user explicitly wants to sort by Year, Title or Rating,
        // they might care less about the hybrid relevance score.
        // But the task says "allow to sort results".

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
                            }%s
                          }
                        },
                        {
                          "standard": {
                            "query": {
                              "semantic": {
                                "field": "plot_elser",
                                "query": "%s"
                              }
                            }%s
                          }
                        }
                      ],
                      "rank_window_size": 100,
                      "rank_constant": 60
                    }
                  }
                }
                """.formatted(
                        from,
                        size,
                        escaped,
                        buildFilterJson(filters),
                        escaped,
                        buildFilterJson(filters)
                );

        // NOTE: RRF with explicit 'sort' is not standardly combined.
        // Usually you either use RRF for relevance OR you use sort for attribute sorting.
        // If sort is specified, we will use standard search for Hybrid? No, let's just stick to standard search if sort is present for Hybrid mode too?
        // Actually, let's check if the client allows sort with retriever.
        // The ES documentation says that 'sort' is not supported when using 'retriever'.

        if (sort != null && !sort.isBlank()) {
            // If sort is requested, we use a bool query with both BM25 and Semantic (as must/should)
            // or just BM25 to keep it simple when sorting by attribute.
            // Let's use BM25 with filters if sort is present, to avoid RRF limitations.
            return searchBm25(queryText, from, size, filters, sort);
        }

        SearchResponse<Movie> response = esClient.search(
                s -> s.index(props.esIndex).withJson(new StringReader(body)),
                Movie.class
        );
        return toSearchResult(response);
    }

    private String buildFilterJson(SearchFilters filters) {
        if (filters == null) return "";
        StringBuilder sb = new StringBuilder();
        List<String> conditions = new java.util.ArrayList<>();

        if (filters.genres() != null && !filters.genres().isEmpty()) {
            String genres = filters.genres().stream().map(g -> "\"" + g + "\"").collect(java.util.stream.Collectors.joining(","));
            conditions.add("{\"terms\": {\"genres\": [" + genres + "]}}");
        }
        if (filters.rating() != null && !filters.rating().isBlank()) {
            conditions.add("{\"term\": {\"rating\": \"" + filters.rating() + "\"}}");
        }
        if (filters.year() != null) {
            conditions.add("{\"range\": {\"release_date\": {\"gte\": \"" + filters.year() + "-01-01\", \"lte\": \"" + filters.year() + "-12-31\"}}}");
        }

        if (conditions.isEmpty()) return "";

        return ", \"filter\": [" + String.join(",", conditions) + "]";
    }

    private List<co.elastic.clients.elasticsearch._types.SortOptions> buildSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return List.of();
        }

        return switch (sort.toUpperCase()) {
            case "RATING" -> List.of(co.elastic.clients.elasticsearch._types.SortOptions.of(s -> s
                    .field(f -> f.field("vote_average").order(co.elastic.clients.elasticsearch._types.SortOrder.Desc))));
            case "YEAR" -> List.of(co.elastic.clients.elasticsearch._types.SortOptions.of(s -> s
                    .field(f -> f.field("release_date").order(co.elastic.clients.elasticsearch._types.SortOrder.Desc))));
            default -> List.of();
        };
    }

    private List<co.elastic.clients.elasticsearch._types.query_dsl.Query> buildFilters(SearchFilters filters) {
        List<co.elastic.clients.elasticsearch._types.query_dsl.Query> esFilters = new java.util.ArrayList<>();
        if (filters == null) return esFilters;

        if (filters.genres() != null && !filters.genres().isEmpty()) {
            esFilters.add(co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q
                    .terms(t -> t
                            .field("genres")
                            .terms(v -> v.value(filters.genres().stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()))
                    )
            ));
        }

        if (filters.rating() != null && !filters.rating().isBlank()) {
            esFilters.add(co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q
                    .term(t -> t
                            .field("rating")
                            .value(filters.rating())
                    )
            ));
        }

        if (filters.year() != null) {
            esFilters.add(co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q
                    .range(r -> r
                            .date(d -> d
                                    .field("release_date")
                                    .gte(filters.year() + "-01-01")
                                    .lte(filters.year() + "-12-31")
                            )
                    )
            ));
        }

        return esFilters;
    }

    public record SearchFilters(List<String> genres, Integer year, String rating) {}

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
