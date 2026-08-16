package com.elastiflix.service;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import com.elastiflix.config.AppProperties;
import com.elastiflix.config.RerankProperties;
import com.elastiflix.exception.InferenceEndpointMissingException;
import com.elastiflix.exception.RerankUnavailableException;
import com.elastiflix.exception.SearchUnavailableException;
import com.elastiflix.model.Movie;
import com.elastiflix.model.MovieSearchPage;
import com.elastiflix.model.SearchMode;
import com.elastiflix.repository.MovieRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MovieServiceTest {

    private static final AppProperties APP_PROPERTIES =
            new AppProperties(50, "https://image.tmdb.org/t/p/w500", "https://image.tmdb.org/t/p/w1280");

    private static final int RERANK_WINDOW = 50;

    private static final RerankProperties RERANK_PROPERTIES =
            new RerankProperties("eis-jina-reranker", RERANK_WINDOW);

    @Mock
    private MovieRepository repository;

    private MovieService service;

    @BeforeEach
    void setUp() {
        service = new MovieService(repository, APP_PROPERTIES, RERANK_PROPERTIES);
    }

    private static MovieRepository.SearchResult result(List<Movie> movies, long totalHits) {
        return new MovieRepository.SearchResult(movies, totalHits, SearchMode.TITLE);
    }

    @Test
    void prefixesRelativePosterAndBackdropPathsWithTheConfiguredBase() throws IOException {
        Movie movie = new Movie();
        movie.setPosterPath("/poster.jpg");
        movie.setBackdropPath("backdrop.jpg");
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(result(List.of(movie), 1));

        MovieSearchPage response = service.search("batman", "TITLE", 1, 50, null, null);

        assertThat(response.getMovies().get(0).getPosterPath()).isEqualTo("https://image.tmdb.org/t/p/w500/poster.jpg");
        assertThat(response.getMovies().get(0).getBackdropPath()).isEqualTo("https://image.tmdb.org/t/p/w1280/backdrop.jpg");
    }

    @Test
    void leavesAlreadyAbsolutePosterPathsUntouched() throws IOException {
        Movie movie = new Movie();
        movie.setPosterPath("https://cdn.example.com/poster.jpg");
        when(repository.findById("123")).thenReturn(Optional.of(movie));

        Optional<Movie> result = service.findById("123");

        assertThat(result).isPresent();
        assertThat(result.get().getPosterPath()).isEqualTo("https://cdn.example.com/poster.jpg");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsABlankQueryAtTheServiceBoundary(String query) {
        // Callers handle an empty search box themselves; reaching the repository with
        // a null query would only produce a confusing cluster-side failure.
        assertThatThrownBy(() -> service.search(query, "TITLE", 1, 50, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null or blank");
    }

    @Test
    void clampsPageToAtLeastOne() throws IOException {
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(result(List.of(), 0));

        MovieSearchPage response = service.search("batman", "TITLE", -5, 50, null, null);

        assertThat(response.getCurrentPage()).isEqualTo(1);
    }

    @Test
    void fallsBackToTheDefaultPageSizeWhenRequestedSizeIsOutOfBounds() throws IOException {
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(result(List.of(), 0));

        MovieSearchPage omitted = service.search("batman", "TITLE", 1, 0, null, null);
        MovieSearchPage tooLarge = service.search("batman", "TITLE", 1, 10_000, null, null);

        assertThat(omitted.getPageSize()).isEqualTo(APP_PROPERTIES.pageSize());
        assertThat(tooLarge.getPageSize()).isEqualTo(APP_PROPERTIES.pageSize());
    }

    @Test
    void clampsPageSoTheRequestStaysWithinElasticsearchsResultWindow() throws IOException {
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(result(List.of(), 0));

        MovieSearchPage response = service.search("batman", "TITLE", 999_999, 100, null, null);

        // from + size must stay <= 10_000, so with size 100 the last reachable page is 100.
        assertThat(response.getCurrentPage()).isEqualTo(100);
    }

    @Test
    void reportsTheModeTheRepositoryActuallyRan() throws IOException {
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new MovieRepository.SearchResult(List.of(), 0, SearchMode.BM25));

        MovieSearchPage response = service.search("batman", "HYBRID", 1, 50, null, "RATING");

        assertThat(response.getMode()).isEqualTo("HYBRID");
        assertThat(response.getEffectiveMode()).isEqualTo("BM25");
    }

    @Test
    void dropsAHomepageThatIsNotHttpSoItCannotBecomeAClickableScript() throws IOException {
        Movie movie = new Movie();
        movie.setHomepage("javascript:alert(document.cookie)");
        when(repository.findById("123")).thenReturn(Optional.of(movie));

        assertThat(service.findById("123")).get()
                .extracting(Movie::getHomepage)
                .isNull();
    }

    @Test
    void keepsAnOrdinaryHttpHomepage() throws IOException {
        Movie movie = new Movie();
        movie.setHomepage("https://www.thedarkknight.com");
        when(repository.findById("123")).thenReturn(Optional.of(movie));

        assertThat(service.findById("123")).get()
                .extracting(Movie::getHomepage)
                .isEqualTo("https://www.thedarkknight.com");
    }

    @Test
    void sanitizesHomepagesOnSearchResultsToo() throws IOException {
        Movie movie = new Movie();
        movie.setHomepage("data:text/html,<script>alert(1)</script>");
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(result(List.of(movie), 1));

        MovieSearchPage response = service.search("batman", "TITLE", 1, 50, null, null);

        assertThat(response.getMovies().get(0).getHomepage()).isNull();
    }

    @Test
    void translatesMissingInferenceEndpointErrorsIntoInferenceEndpointMissingException() throws IOException {
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(esException("resource_not_found_exception", "Inference endpoint not found [elser]"));

        assertThatThrownBy(() -> service.search("batman", "ELSER", 1, 50, null, null))
                .isInstanceOf(InferenceEndpointMissingException.class)
                .hasMessageContaining("elser");
    }

    @Test
    void namesTheElserEndpointWhenHybridIsTheFailingMode() throws IOException {
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(esException("resource_not_found_exception", "Inference endpoint not found [elser]"));

        assertThatThrownBy(() -> service.search("batman", "HYBRID", 1, 50, null, null))
                .isInstanceOf(InferenceEndpointMissingException.class)
                .hasMessageContaining("hybrid RRF");
    }

    @Test
    void namesTheE5EndpointWhenE5IsTheFailingMode() throws IOException {
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(esException("resource_not_found_exception", "Inference endpoint not found [e5]"));

        assertThatThrownBy(() -> service.search("batman", "E5", 1, 50, null, null))
                .isInstanceOf(InferenceEndpointMissingException.class)
                .hasMessageContaining("e5");
    }

    @Test
    void namesBothEndpointsWhenTheRerankedModeIsMissingOne() throws IOException {
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(esException("resource_not_found_exception", "Inference endpoint not found [eis-jina-reranker]"));

        assertThatThrownBy(() -> service.search("batman", "ELSER_JINA", 1, 25, null, null))
                .isInstanceOf(InferenceEndpointMissingException.class)
                .hasMessageContaining("elser")
                .hasMessageContaining("eis-jina-reranker");
    }

    @Test
    void reportsARefusedRerankKeySeparatelyFromAClusterOutage() throws IOException {
        // The endpoint exists; the provider rejected the credentials. Telling the user
        // "search is unavailable" would send them debugging Elasticsearch instead.
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(esException("status_exception", "Received a 401 response: invalid api key"));

        assertThatThrownBy(() -> service.search("batman", "ELSER_JINA", 1, 25, null, null))
                .isInstanceOf(RerankUnavailableException.class)
                .hasMessageContaining("eis-jina-reranker")
                .hasMessageContaining("authentication was refused");
    }

    @Test
    void reportsARateLimitedRerankerSeparately() throws IOException {
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(esException("status_exception", "Received a 429 response: rate limit exceeded"));

        assertThatThrownBy(() -> service.search("batman", "ELSER_JINA", 1, 25, null, null))
                .isInstanceOf(RerankUnavailableException.class)
                .hasMessageContaining("rate limited or out of quota");
    }

    @Test
    void doesNotBlameTheRerankerForFailuresInNonRerankedModes() throws IOException {
        // The same signature in a BM25 search is not about a rerank provider.
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(esException("status_exception", "Received a 403 response from the cluster"));

        assertThatThrownBy(() -> service.search("batman", "BM25", 1, 25, null, null))
                .isInstanceOf(SearchUnavailableException.class);
    }

    @Test
    void namesTheRerankEndpointWhenOnlyTheRerankerIsMissing() throws IOException {
        // Elasticsearch reports a retriever failure as a wrapper and hides the real cause in a
        // suppressed exception, so nothing in getMessage() mentions inference. Classifying on
        // the message alone told the user "search is unavailable" instead of naming the endpoint.
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(wrappedRerankFailure(
                        "resource_not_found_exception", "Inference endpoint not found [eis-jina-reranker]"));

        assertThatThrownBy(() -> service.search("batman", "ELSER_JINA", 1, 25, null, null))
                .isInstanceOf(InferenceEndpointMissingException.class)
                .hasMessageContaining("eis-jina-reranker");
    }

    @Test
    void reportsARefusedRerankKeyEvenThroughTheRetrieverWrapper() throws IOException {
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(wrappedRerankFailure("status_exception",
                        "Received an authentication error status code for request [401]"));

        assertThatThrownBy(() -> service.search("batman", "ELSER_JINA", 1, 25, null, null))
                .isInstanceOf(RerankUnavailableException.class)
                .hasMessageContaining("authentication was refused");
    }

    @Test
    void stillReportsAGenericOutageWhenTheWrappedCauseIsAnOrdinaryFailure() throws IOException {
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(wrappedRerankFailure("search_phase_execution_exception", "all shards failed"));

        assertThatThrownBy(() -> service.search("batman", "ELSER_JINA", 1, 25, null, null))
                .isInstanceOf(SearchUnavailableException.class);
    }

    /**
     * The shape a {@code text_similarity_reranker} failure really has: a bare wrapper on top,
     * the cause two levels down. Copied from a live 8.17.0 cluster.
     */
    private static ElasticsearchException wrappedRerankFailure(String causeType, String causeReason) {
        return new ElasticsearchException("es/search", ErrorResponse.of(r -> r
                .status(500)
                .error(e -> e
                        .type("status_exception")
                        .reason("[text_similarity_reranker] search failed - retrievers '[standard]' returned "
                                + "errors. All failures are attached as suppressed exceptions.")
                        .suppressed(s -> s
                                .type("search_phase_execution_exception")
                                .reason("Computing updated ranks for results failed")
                                .causedBy(c -> c.type(causeType).reason(causeReason))))));
    }

    @Test
    void capsThePageAtTheRerankWindowBecauseNothingBeyondItIsScored() throws IOException {
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new MovieRepository.SearchResult(List.of(), 5_000, SearchMode.ELSER_JINA));

        // Window 50 at 25 per page leaves exactly two reachable pages.
        MovieSearchPage response = service.search("batman", "ELSER_JINA", 99, 25, null, null);

        assertThat(response.getCurrentPage()).isEqualTo(2);
        assertThat(response.getTotalPages()).isEqualTo(2);
        assertThat(response.isWindowLimited()).isTrue();
        assertThat(response.getReachableDocuments()).isEqualTo(RERANK_WINDOW);
    }

    @Test
    void doesNotCrashWhenThePageIsLargerThanTheRerankWindow() throws IOException {
        // Window 50 with 100 per page: fewer than one full page is reachable, and the
        // clamp must still produce a valid range rather than min > max.
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new MovieRepository.SearchResult(List.of(), 5_000, SearchMode.ELSER_JINA));

        MovieSearchPage response = service.search("batman", "ELSER_JINA", 3, 100, null, null);

        assertThat(response.getCurrentPage()).isEqualTo(1);
        assertThat(response.getReachableDocuments()).isEqualTo(RERANK_WINDOW);
    }

    @Test
    void restoresTheFullResultWindowWhenTheRerankedModeDegradesToBm25() throws IOException {
        // With a sort the reranker never runs, so its narrow window must not constrain paging.
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new MovieRepository.SearchResult(List.of(), 5_000, SearchMode.BM25));

        MovieSearchPage response = service.search("batman", "ELSER_JINA", 50, 25, null, "RATING");

        assertThat(response.getCurrentPage()).isEqualTo(50);
        assertThat(response.isWindowLimited()).isFalse();
    }

    @Test
    void hidesRawClusterErrorDetailsFromTheUserFacingMessage() throws IOException {
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(esException("search_phase_execution_exception", "all shards failed"));

        assertThatThrownBy(() -> service.search("batman", "TITLE", 1, 50, null, null))
                .isInstanceOf(SearchUnavailableException.class)
                .hasMessage("Search is temporarily unavailable. Please try again later.");
    }

    private static ElasticsearchException esException(String type, String reason) {
        return new ElasticsearchException("es/search", ErrorResponse.of(r -> r
                .error(e -> e.type(type).reason(reason))
                .status(500)));
    }

    @Test
    void translatesIOExceptionIntoSearchUnavailableException() throws IOException {
        when(repository.search(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new IOException("connection refused"));

        assertThatThrownBy(() -> service.search("batman", "TITLE", 1, 50, null, null))
                .isInstanceOf(SearchUnavailableException.class);
    }

    @Test
    void translatesIOExceptionOnLookupIntoSearchUnavailableException() throws IOException {
        when(repository.findById("123")).thenThrow(new IOException("connection refused"));

        assertThatThrownBy(() -> service.findById("123"))
                .isInstanceOf(SearchUnavailableException.class);
    }

}
