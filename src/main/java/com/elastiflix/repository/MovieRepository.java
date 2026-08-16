package com.elastiflix.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.RRFRetrieverEntry;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.elastiflix.config.ElasticsearchProperties;
import com.elastiflix.config.RerankProperties;
import com.elastiflix.model.Movie;
import com.elastiflix.model.MovieGenre;
import com.elastiflix.model.ReleaseYear;
import com.elastiflix.model.SearchMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Executes movie searches against Elasticsearch, translating each
 * {@link SearchMode} into the appropriate query or retriever shape using the
 * client's typed query DSL.
 */
@Repository
public class MovieRepository {

    private static final Logger log = LoggerFactory.getLogger(MovieRepository.class);

    /** Minimum {@code rank_window_size} used for the Hybrid (RRF) retriever. */
    private static final int RRF_RANK_WINDOW_SIZE = 100;

    /** {@code rank_constant} used for the Hybrid (RRF) retriever. */
    private static final int RRF_RANK_CONSTANT = 60;

    /** Field carrying ELSER sparse embeddings. */
    private static final String ELSER_FIELD = "plot_elser";

    /** Field carrying E5 dense embeddings. */
    private static final String E5_FIELD = "plot_e5";

    /** Field whose text is sent to the reranker to score against the query. */
    private static final String RERANK_FIELD = "plot";

    private final ElasticsearchClient esClient;
    private final ElasticsearchProperties esProperties;
    private final RerankProperties rerankProperties;

    public MovieRepository(ElasticsearchClient esClient, ElasticsearchProperties esProperties,
                           RerankProperties rerankProperties) {
        this.esClient = esClient;
        this.esProperties = esProperties;
        this.rerankProperties = rerankProperties;
    }

    /**
     * Runs a search for the given mode, applying pagination, filters and sort.
     *
     * @return the hits, together with the mode that actually ran — see
     *         {@link #degradesToBm25} for the cases where it differs from {@code mode}
     * @throws IOException if the Elasticsearch request fails or the cluster is unreachable
     */
    public SearchResult search(String queryText, SearchMode mode, int page, int size, SearchFilters filters, String sort) throws IOException {
        int from = (page - 1) * size;
        log.debug("Searching mode={} page={} size={} sort={}", mode, page, size, sort);

        return switch (mode) {
            case TITLE      -> searchTitle(queryText, from, size, filters, sort);
            case BM25       -> searchBm25(queryText, from, size, filters, sort);
            case ELSER      -> searchSemantic(queryText, SearchMode.ELSER, ELSER_FIELD, from, size, filters, sort);
            case E5         -> searchSemantic(queryText, SearchMode.E5, E5_FIELD, from, size, filters, sort);
            case HYBRID     -> searchHybrid(queryText, from, size, filters, sort);
            case ELSER_JINA -> searchElserJina(queryText, from, size, filters, sort);
        };
    }

    /**
     * Whether a mode has to fall back to plain BM25 for this request.
     *
     * <p>Elasticsearch rejects a top-level {@code sort} next to a {@code retriever}, and
     * ranking by rating or release date makes relevance fusion or reranking pointless
     * anyway. Shared with the service layer, which needs the same answer to work out how
     * deep the results can be paged.
     */
    public static boolean degradesToBm25(SearchMode mode, String sort) {
        return mode.usesRetriever() && sort != null && !sort.isBlank();
    }

    /** The {@code semantic} query shared by the ELSER, E5, Hybrid and reranked modes. */
    private static Query semanticQuery(String field, String queryText) {
        return Query.of(q -> q.semantic(sem -> sem
                .field(field)
                .query(queryText)
        ));
    }

    private SearchResult searchTitle(String queryText, int from, int size, SearchFilters filters, String sort) throws IOException {
        SearchResponse<Movie> response = esClient.search(s -> s
                        .index(esProperties.index())
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
        return toSearchResult(response, SearchMode.TITLE);
    }

    private SearchResult searchBm25(String queryText, int from, int size, SearchFilters filters, String sort) throws IOException {
        SearchResponse<Movie> response = esClient.search(s -> s
                        .index(esProperties.index())
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
        return toSearchResult(response, SearchMode.BM25);
    }

    /**
     * Semantic search (ELSER or E5, depending on {@code field}) using the client's
     * typed {@code semantic} query — no hand-built JSON, so user input never needs
     * manual escaping.
     */
    private SearchResult searchSemantic(String queryText, SearchMode mode, String field, int from, int size,
                                        SearchFilters filters, String sort) throws IOException {
        SearchResponse<Movie> response = esClient.search(s -> s
                        .index(esProperties.index())
                        .from(from)
                        .size(size)
                        .query(q -> q
                                .bool(b -> b
                                        .must(semanticQuery(field, queryText))
                                        .filter(buildFilters(filters))
                                )
                        )
                        .sort(buildSort(sort)),
                Movie.class
        );
        return toSearchResult(response, mode);
    }

    /**
     * Hybrid search: combines BM25 and ELSER via the RRF retriever (ES 8.14+).
     *
     * <p>RRF does not support attribute sort — when the caller asks to sort by
     * rating or year, relevance ranking is meaningless anyway, so we fall back
     * to plain BM25 with the same filters instead of fighting the retriever API.
     * The returned {@link SearchResult#effectiveMode()} says so, which is how the
     * UI is able to tell the user their Hybrid selection quietly became BM25.
     */
    private SearchResult searchHybrid(String queryText, int from, int size, SearchFilters filters, String sort) throws IOException {
        if (degradesToBm25(SearchMode.HYBRID, sort)) {
            return searchBm25(queryText, from, size, filters, sort);
        }

        List<Query> esFilters = buildFilters(filters);

        // RRF requires from + size <= rank_window_size, so grow the window when paginating deep.
        int rankWindowSize = Math.max(RRF_RANK_WINDOW_SIZE, from + size);

        List<RRFRetrieverEntry> retrievers = List.of(
                RRFRetrieverEntry.of(e -> e.retriever(rt -> rt
                        .standard(st -> st
                                .query(q -> q.multiMatch(mm -> mm
                                        .query(queryText)
                                        .fields(List.of("title^3", "original_title^2", "overview", "plot"))
                                ))
                                .filter(esFilters)
                        )
                )),
                RRFRetrieverEntry.of(e -> e.retriever(rt -> rt
                        .standard(st -> st
                                .query(semanticQuery(ELSER_FIELD, queryText))
                                .filter(esFilters)
                        )
                ))
        );

        SearchResponse<Movie> response = esClient.search(s -> s
                        .index(esProperties.index())
                        .from(from)
                        .size(size)
                        .retriever(r -> r
                                .rrf(rrf -> rrf
                                        .retrievers(retrievers)
                                        .rankWindowSize(rankWindowSize)
                                        .rankConstant(RRF_RANK_CONSTANT)
                                )
                        ),
                Movie.class
        );
        return toSearchResult(response, SearchMode.HYBRID);
    }

    /**
     * Two-stage search: ELSER retrieves candidates, then a reranking inference endpoint
     * (by default {@code eis-jina-reranker}, an Elastic Inference Service endpoint running
     * {@code jina-reranker-v3.5}) rescores each candidate's {@code plot} against the query.
     *
     * <p>Unlike every other mode, the page size is not the only limit on what comes back:
     * {@code rank_window_size} caps how many candidates are reranked, and documents outside
     * that window are never returned. The window is therefore left fixed rather than grown to
     * cover {@code from + size} the way the RRF retriever's is. Widening it per request would
     * change the candidate set — and so the ranking — from one page to the next, and it is
     * bounded anyway: the model is listwise, scoring the query and every candidate in one
     * shared context. The service layer clamps the page to match, and the view says so.
     *
     * <p>Filters are attached to the inner retriever so they narrow the candidate set
     * before it enters the rerank window — a filter on the reranker itself would spend
     * window slots on documents that get discarded afterwards.
     */
    private SearchResult searchElserJina(String queryText, int from, int size, SearchFilters filters, String sort) throws IOException {
        if (degradesToBm25(SearchMode.ELSER_JINA, sort)) {
            return searchBm25(queryText, from, size, filters, sort);
        }

        List<Query> esFilters = buildFilters(filters);
        int rankWindowSize = rerankProperties.windowSize();

        SearchResponse<Movie> response = esClient.search(s -> s
                        .index(esProperties.index())
                        .from(from)
                        .size(size)
                        .retriever(r -> r
                                .textSimilarityReranker(rr -> rr
                                        .retriever(inner -> inner
                                                .standard(st -> st
                                                        .query(semanticQuery(ELSER_FIELD, queryText))
                                                        .filter(esFilters)
                                                )
                                        )
                                        .field(RERANK_FIELD)
                                        .inferenceId(rerankProperties.inferenceId())
                                        .inferenceText(queryText)
                                        .rankWindowSize(rankWindowSize)
                                )
                        ),
                Movie.class
        );
        return toSearchResult(response, SearchMode.ELSER_JINA);
    }

    /** Builds the typed filter clauses shared by every search mode. */
    List<Query> buildFilters(SearchFilters filters) {
        List<Query> esFilters = new ArrayList<>();
        if (filters == null) {
            return esFilters;
        }

        if (filters.genres() != null && !filters.genres().isEmpty()) {
            // terms is not analysed, so the casing has to match the index exactly.
            List<FieldValue> genreTerms = filters.genres().stream()
                    .map(MovieGenre::canonicalize)
                    .filter(Objects::nonNull)
                    .map(FieldValue::of)
                    .toList();
            if (!genreTerms.isEmpty()) {
                esFilters.add(Query.of(q -> q
                        .terms(t -> t
                                .field("genres")
                                .terms(v -> v.value(genreTerms))
                        )
                ));
            }
        }

        if (filters.rating() != null && !filters.rating().isBlank()) {
            esFilters.add(Query.of(q -> q
                    .term(t -> t
                            .field("rating")
                            .value(filters.rating())
                    )
            ));
        }

        if (filters.year() != null && ReleaseYear.isPlausible(filters.year().intValue())) {
            esFilters.add(Query.of(q -> q
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

    /** Maps the user-facing {@code sort} parameter to Elasticsearch sort options. */
    List<SortOptions> buildSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return List.of();
        }

        return switch (sort.toUpperCase(Locale.ROOT)) {
            case "RATING" -> List.of(SortOptions.of(s -> s
                    .field(f -> f.field("vote_average").order(SortOrder.Desc))));
            case "YEAR" -> List.of(SortOptions.of(s -> s
                    .field(f -> f.field("release_date").order(SortOrder.Desc))));
            default -> List.of();
        };
    }

    /** Optional filter criteria shared by every search mode. */
    public record SearchFilters(List<String> genres, Integer year, String rating) {}

    /**
     * Looks up a single movie by its {@code id} keyword field (not the ES {@code _id},
     * which may differ from the domain identifier).
     *
     * <p>Fetches two hits rather than one purely so a duplicated {@code id} in the
     * index is visible in the logs instead of silently resolving to whichever
     * document happened to score first.
     */
    public Optional<Movie> findById(String id) throws IOException {
        SearchResponse<Movie> response = esClient.search(s -> s
                        .index(esProperties.index())
                        .size(2)
                        .query(q -> q
                                .term(t -> t
                                        .field("id")
                                        .value(id)
                                )
                        ),
                Movie.class
        );

        List<Hit<Movie>> hits = response.hits().hits();
        if (hits.size() > 1) {
            log.warn("Index '{}' holds more than one document with id={}; returning the first hit. " +
                    "The id field is expected to be unique.", esProperties.index(), id);
        }

        return hits.stream()
                .findFirst()
                .map(Hit::source);
    }

    private SearchResult toSearchResult(SearchResponse<Movie> response, SearchMode effectiveMode) {
        List<Movie> movies = response.hits().hits().stream()
                .map(Hit::source)
                .toList();
        long total = response.hits().total() != null
                ? response.hits().total().value()
                : 0L;
        return new SearchResult(movies, total, effectiveMode);
    }

    /**
     * A single page of raw search hits, before pagination metadata is added by the
     * service layer.
     *
     * @param effectiveMode the strategy that actually ran, which is not necessarily
     *                      the one requested
     */
    public record SearchResult(List<Movie> movies, long totalHits, SearchMode effectiveMode) {}
}
