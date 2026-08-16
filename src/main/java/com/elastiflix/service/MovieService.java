package com.elastiflix.service;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.elastiflix.config.AppProperties;
import com.elastiflix.config.RerankProperties;
import com.elastiflix.exception.ElasticsearchErrors;
import com.elastiflix.exception.InferenceEndpointMissingException;
import com.elastiflix.exception.RerankUnavailableException;
import com.elastiflix.exception.SearchUnavailableException;
import com.elastiflix.model.Movie;
import com.elastiflix.model.MovieSearchPage;
import com.elastiflix.model.SearchMode;
import com.elastiflix.repository.MovieRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Orchestrates movie search and lookup: delegates the query itself to
 * {@link MovieRepository}, enriches poster/backdrop URLs, and translates
 * low-level Elasticsearch failures into the domain exceptions defined in
 * {@code com.elastiflix.exception}.
 */
@Service
public class MovieService {

    private static final Logger log = LoggerFactory.getLogger(MovieService.class);

    private final MovieRepository repository;
    private final AppProperties props;
    private final RerankProperties rerankProperties;

    public MovieService(MovieRepository repository, AppProperties props, RerankProperties rerankProperties) {
        this.repository = repository;
        this.props = props;
        this.rerankProperties = rerankProperties;
    }

    /**
     * Searches movies for the given mode, clamping {@code page}/{@code size} to safe
     * bounds and enriching each result's poster/backdrop paths into absolute URLs.
     *
     * @throws IllegalArgumentException if {@code query} is null or blank — callers are
     *         expected to handle an empty search box themselves rather than send it here
     * @throws SearchUnavailableException if Elasticsearch cannot be reached
     * @throws InferenceEndpointMissingException if the requested mode needs an ML
     *         inference endpoint that is not deployed in the cluster
     */
    public MovieSearchPage search(String query, String mode, int page, int size, MovieRepository.SearchFilters filters, String sort) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be null or blank");
        }

        SearchMode searchMode = SearchMode.fromString(mode);
        int safeSize = clampPageSize(size);
        int reachableDocuments = reachableDocuments(searchMode, sort);
        int safePage = clampPage(page, safeSize, reachableDocuments);

        try {
            MovieRepository.SearchResult result = repository.search(query, searchMode, safePage, safeSize, filters, sort);
            enrichMovies(result.movies());
            return MovieSearchPage.of(result.movies(), result.totalHits(), safePage, safeSize, query,
                    searchMode.name(), result.effectiveMode().name(), reachableDocuments);
        } catch (ElasticsearchException e) {
            throw translateElasticsearchException(e, searchMode);
        } catch (IOException e) {
            log.error("Could not reach Elasticsearch while searching mode={} query='{}'", searchMode, query, e);
            throw new SearchUnavailableException("Could not reach Elasticsearch. Please try again later.", e);
        }
    }

    /**
     * Looks up a single movie by id, enriching its poster/backdrop paths if found.
     *
     * @throws SearchUnavailableException if Elasticsearch cannot be reached
     */
    public Optional<Movie> findById(String id) {
        try {
            Optional<Movie> movie = repository.findById(id);
            movie.ifPresent(this::enrichMovie);
            return movie;
        } catch (IOException e) {
            log.error("Could not reach Elasticsearch while looking up movie id={}", id, e);
            throw new SearchUnavailableException("Could not reach Elasticsearch. Please try again later.", e);
        }
    }

    /**
     * How many documents this request can retrieve at most.
     *
     * <p>A reranked mode only scores {@code rank_window_size} candidates and cannot return
     * anything outside that window, so it is a far tighter bound than the cluster's result
     * window — unless the mode degrades to BM25, in which case the usual bound applies again.
     */
    private int reachableDocuments(SearchMode mode, String sort) {
        boolean rerankWillRun = mode.isReranked() && !MovieRepository.degradesToBm25(mode, sort);
        return rerankWillRun ? rerankProperties.windowSize() : MovieSearchPage.MAX_RESULT_WINDOW;
    }

    /** Clamps the page so it is at least 1 and {@code from + size} stays inside the retrievable window. */
    private static int clampPage(int requestedPage, int pageSize, int reachableDocuments) {
        // At least one page, or a window narrower than a single page would produce an
        // empty range and Math.clamp would reject min > max.
        int lastReachablePage = Math.max(1, reachableDocuments / pageSize);
        return Math.clamp(requestedPage, 1, lastReachablePage);
    }

    private int clampPageSize(int requestedSize) {
        int effective = props.effectivePageSize(requestedSize);
        if (effective != requestedSize) {
            log.debug("Requested page size {} outside [{}, {}], falling back to default {}",
                    requestedSize, AppProperties.MIN_PAGE_SIZE, AppProperties.MAX_PAGE_SIZE, effective);
        }
        return effective;
    }

    /**
     * Turns a cluster error into the domain exception that describes it.
     *
     * <p>Classification reads the structured error tree rather than
     * {@link ElasticsearchException#getMessage()} — a retriever hides the real cause under a
     * wrapper, so the flattened message says nothing useful. See {@link ElasticsearchErrors}.
     */
    private RuntimeException translateElasticsearchException(ElasticsearchException e, SearchMode mode) {
        log.warn("Elasticsearch error during search [mode={}]: status={} message={}", mode, e.status(), e.getMessage());
        if (ElasticsearchErrors.mentionsMissingInferenceEndpoint(e)) {
            return new InferenceEndpointMissingException(inferenceEndpointName(mode), e);
        }
        // Only reranked modes call an inference provider that can reject us on credentials
        // or quota; for the others these signatures would be a false positive.
        if (mode.isReranked()) {
            String rejection = ElasticsearchErrors.rerankRejectionReason(e);
            if (rejection != null) {
                return new RerankUnavailableException(rerankProperties.inferenceId(), rejection, e);
            }
        }
        // ElastiflixException messages are user-facing: keep the raw cluster error in the log only.
        return new SearchUnavailableException("Search is temporarily unavailable. Please try again later.", e);
    }

    private String inferenceEndpointName(SearchMode mode) {
        return switch (mode) {
            case ELSER      -> "elser";
            case E5         -> "e5";
            case HYBRID     -> "elser (required for hybrid RRF)";
            case ELSER_JINA -> "elser and " + rerankProperties.inferenceId() + " (required for reranking)";
            default         -> mode.name().toLowerCase(Locale.ROOT);
        };
    }

    private void enrichMovies(List<Movie> movies) {
        movies.forEach(this::enrichMovie);
    }

    private void enrichMovie(Movie movie) {
        enrichPosterPath(movie);
        sanitizeHomepage(movie);
    }

    private void enrichPosterPath(Movie movie) {
        if (movie.getPosterPath() != null && !isAbsoluteUrl(movie.getPosterPath())) {
            movie.setPosterPath(props.tmdbImageBase() + toAbsolutePath(movie.getPosterPath()));
        }
        if (movie.getBackdropPath() != null && !isAbsoluteUrl(movie.getBackdropPath())) {
            movie.setBackdropPath(props.tmdbImageBaseLarge() + toAbsolutePath(movie.getBackdropPath()));
        }
    }

    /**
     * Drops a {@code homepage} that is not plain HTTP(S).
     *
     * <p>The detail page renders this value straight into an {@code href}. Thymeleaf
     * escapes it but does not restrict the scheme, so a document carrying a
     * {@code javascript:} URL would turn into a clickable script-execution link —
     * stored XSS sourced from the index rather than from the request.
     */
    private void sanitizeHomepage(Movie movie) {
        String homepage = movie.getHomepage();
        if (homepage != null && !isAbsoluteUrl(homepage.trim().toLowerCase(Locale.ROOT))) {
            log.debug("Dropping non-HTTP(S) homepage on movie id={}", movie.getId());
            movie.setHomepage(null);
        }
    }

    private static boolean isAbsoluteUrl(String path) {
        return path.startsWith("http://") || path.startsWith("https://");
    }

    private static String toAbsolutePath(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }
}
