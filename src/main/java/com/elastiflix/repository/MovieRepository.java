package com.elastiflix.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.elastiflix.config.AppProperties;
import com.elastiflix.model.Movie;
import com.elastiflix.model.SearchMode;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class MovieRepository {

    private static final String GENRES_AGG = "genres";
    private static final String YEARS_AGG = "years";

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
        SearchResponse<Movie> response = esClient.search(s -> {
            s.index(props.esIndex)
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
                                    .filter(buildNonGenreFilters(filters))
                            )
                    )
                    .aggregations(GENRES_AGG, genresAggregation())
                    .aggregations(YEARS_AGG, yearsAggregation())
                    .sort(buildSort(sort));
            Query postFilter = buildPostFilter(filters);
            if (postFilter != null) {
                s.postFilter(postFilter);
            }
            return s;
        }, Movie.class);
        return toSearchResult(response);
    }

    private SearchResult searchBm25(String queryText, int from, int size, SearchFilters filters, String sort) throws IOException {
        SearchResponse<Movie> response = esClient.search(s -> {
            s.index(props.esIndex)
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
                                    .filter(buildNonGenreFilters(filters))
                            )
                    )
                    .aggregations(GENRES_AGG, genresAggregation())
                    .aggregations(YEARS_AGG, yearsAggregation())
                    .sort(buildSort(sort));
            Query postFilter = buildPostFilter(filters);
            if (postFilter != null) {
                s.postFilter(postFilter);
            }
            return s;
        }, Movie.class);
        return toSearchResult(response);
    }

    private SearchResult searchSemantic(String queryText, String field, int from, int size, SearchFilters filters, String sort) throws IOException {
        String escaped = queryText.replace("\\", "\\\\").replace("\"", "\\\"");

        SearchResponse<Movie> response = esClient.search(s -> {
            s.index(props.esIndex)
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
                                    .filter(buildNonGenreFilters(filters))
                            )
                    )
                    .aggregations(GENRES_AGG, genresAggregation())
                    .aggregations(YEARS_AGG, yearsAggregation())
                    .sort(buildSort(sort));
            Query postFilter = buildPostFilter(filters);
            if (postFilter != null) {
                s.postFilter(postFilter);
            }
            return s;
        }, Movie.class);
        return toSearchResult(response);
    }

    private SearchResult searchHybrid(String queryText, int from, int size, SearchFilters filters, String sort) throws IOException {
        // RRF retrievers don't support sort — fall back to BM25 when sorting requested.
        if (sort != null && !sort.isBlank()) {
            return searchBm25(queryText, from, size, filters, sort);
        }

        String escaped = queryText.replace("\\", "\\\\").replace("\"", "\\\"");
        String nonGenreFilterJson = buildNonGenreFilterJson(filters);
        String postFilterJson = buildPostFilterJson(filters);
        String aggsJson = """
                ,
                "aggs": {
                  "%s": { "terms": { "field": "genres", "size": 100 } },
                  "%s": { "date_histogram": { "field": "release_date", "calendar_interval": "year", "min_doc_count": 1 } }
                }
                """.formatted(GENRES_AGG, YEARS_AGG);

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
                  }%s%s
                }
                """.formatted(
                        from,
                        size,
                        escaped,
                        nonGenreFilterJson,
                        escaped,
                        nonGenreFilterJson,
                        postFilterJson,
                        aggsJson
                );

        SearchResponse<Movie> response = esClient.search(
                s -> s.index(props.esIndex).withJson(new StringReader(body)),
                Movie.class
        );
        return toSearchResult(response);
    }

    private Aggregation genresAggregation() {
        return Aggregation.of(a -> a.terms(t -> t.field("genres").size(100)));
    }

    private Aggregation yearsAggregation() {
        return Aggregation.of(a -> a.dateHistogram(dh -> dh
                .field("release_date")
                .calendarInterval(CalendarInterval.Year)
                .minDocCount(1)
        ));
    }

    private List<Query> buildNonGenreFilters(SearchFilters filters) {
        List<Query> esFilters = new ArrayList<>();
        if (filters == null) return esFilters;

        if (filters.rating() != null && !filters.rating().isBlank()) {
            esFilters.add(Query.of(q -> q
                    .term(t -> t.field("rating").value(filters.rating()))
            ));
        }

        return esFilters;
    }

    private Query buildPostFilter(SearchFilters filters) {
        if (filters == null) return null;
        List<Query> clauses = new ArrayList<>();

        if (filters.genres() != null && !filters.genres().isEmpty()) {
            clauses.add(Query.of(q -> q
                    .terms(t -> t
                            .field("genres")
                            .terms(v -> v.value(filters.genres().stream().map(FieldValue::of).toList()))
                    )
            ));
        }

        if (filters.year() != null) {
            clauses.add(Query.of(q -> q
                    .range(r -> r
                            .date(d -> d
                                    .field("release_date")
                                    .gte(filters.year() + "-01-01")
                                    .lte(filters.year() + "-12-31")
                            )
                    )
            ));
        }

        if (clauses.isEmpty()) return null;
        if (clauses.size() == 1) return clauses.get(0);
        return Query.of(q -> q.bool(BoolQuery.of(b -> b.filter(clauses))));
    }

    private String buildNonGenreFilterJson(SearchFilters filters) {
        if (filters == null) return "";
        List<String> conditions = new ArrayList<>();

        if (filters.rating() != null && !filters.rating().isBlank()) {
            conditions.add("{\"term\": {\"rating\": \"" + filters.rating() + "\"}}");
        }

        if (conditions.isEmpty()) return "";
        return ", \"filter\": [" + String.join(",", conditions) + "]";
    }

    private String buildPostFilterJson(SearchFilters filters) {
        if (filters == null) return "";
        List<String> clauses = new ArrayList<>();

        if (filters.genres() != null && !filters.genres().isEmpty()) {
            String genres = filters.genres().stream()
                    .map(g -> "\"" + g.replace("\"", "\\\"") + "\"")
                    .collect(Collectors.joining(","));
            clauses.add("{\"terms\": {\"genres\": [" + genres + "]}}");
        }

        if (filters.year() != null) {
            clauses.add("{\"range\": {\"release_date\": {\"gte\": \"" + filters.year() + "-01-01\", \"lte\": \"" + filters.year() + "-12-31\"}}}");
        }

        if (clauses.isEmpty()) return "";
        if (clauses.size() == 1) return ",\n  \"post_filter\": " + clauses.get(0);
        return ",\n  \"post_filter\": { \"bool\": { \"filter\": [" + String.join(",", clauses) + "] } }";
    }

    private List<SortOptions> buildSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return List.of();
        }

        return switch (sort.toUpperCase()) {
            case "RATING" -> List.of(SortOptions.of(s -> s
                    .field(f -> f.field("vote_average").order(SortOrder.Desc))));
            case "YEAR" -> List.of(SortOptions.of(s -> s
                    .field(f -> f.field("release_date").order(SortOrder.Desc))));
            default -> List.of();
        };
    }

    public record SearchFilters(List<String> genres, Integer year, String rating) {}

    public Optional<Movie> findById(String id) throws IOException {
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

        List<String> availableGenres = List.of();
        if (response.aggregations() != null && response.aggregations().get(GENRES_AGG) != null) {
            availableGenres = response.aggregations().get(GENRES_AGG).sterms().buckets().array().stream()
                    .map(StringTermsBucket::key)
                    .map(FieldValue::stringValue)
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }

        List<Integer> availableYears = List.of();
        if (response.aggregations() != null && response.aggregations().get(YEARS_AGG) != null) {
            availableYears = response.aggregations().get(YEARS_AGG).dateHistogram().buckets().array().stream()
                    .map(b -> Instant.ofEpochMilli(b.key()).atZone(ZoneOffset.UTC).getYear())
                    .distinct()
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }

        return new SearchResult(movies, total, availableGenres, availableYears);
    }

    public record SearchResult(List<Movie> movies, long totalHits,
                               List<String> availableGenres, List<Integer> availableYears) {}
}
