package com.elastiflix.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.RRFRetrieverEntry;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.util.ObjectBuilder;
import com.elastiflix.config.ElasticsearchProperties;
import com.elastiflix.config.RerankProperties;
import com.elastiflix.model.Movie;
import com.elastiflix.model.SearchMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Year;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the request each {@link SearchMode} produces.
 *
 * <p>The mocked client applies the builder lambda the repository passes it and keeps
 * the resulting {@link SearchRequest}, so the assertions below inspect the very
 * object that would have gone to Elasticsearch — field boosts, filters, sort,
 * pagination and the RRF retriever included. {@code MovieRepositoryIT} covers the
 * other half: that a real cluster accepts these shapes.
 */
class MovieRepositoryTest {

    private static final String INDEX = "elastiflix-movies";

    private final ElasticsearchClient esClient = mock(ElasticsearchClient.class);

    private static final int RERANK_WINDOW = 50;
    private static final String RERANK_ID = "eis-jina-reranker";

    private final MovieRepository repository = new MovieRepository(
            esClient,
            new ElasticsearchProperties("https://localhost:9200", "test-key", INDEX, true, null, null),
            new RerankProperties(RERANK_ID, RERANK_WINDOW)
    );

    private SearchRequest captured;

    @BeforeEach
    void stubTheClient() throws IOException {
        stubResponse(emptyResponse());
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(SearchResponse<Movie> response) throws IOException {
        when(esClient.search(any(Function.class), eq(Movie.class))).thenAnswer(invocation -> {
            Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> builder = invocation.getArgument(0);
            captured = builder.apply(new SearchRequest.Builder()).build();
            return response;
        });
    }

    private static SearchResponse<Movie> emptyResponse() {
        return SearchResponse.of(r -> r
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(List.of()).total(t -> t.value(0).relation(TotalHitsRelation.Eq))));
    }

    private static SearchResponse<Movie> responseWith(Movie... movies) {
        List<Hit<Movie>> hits = Arrays.stream(movies)
                .map(movie -> Hit.<Movie>of(h -> h.index(INDEX).id(movie.getId()).source(movie)))
                .toList();
        return SearchResponse.of(r -> r
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(hits).total(t -> t.value(hits.size()).relation(TotalHitsRelation.Eq))));
    }

    private static Movie movie(String id) {
        Movie movie = new Movie();
        movie.setId(id);
        movie.setTitle("The Dark Knight");
        return movie;
    }

    private static MovieRepository.SearchFilters noFilters() {
        return new MovieRepository.SearchFilters(null, null, null);
    }

    // ---------------------------------------------------------------- query shapes

    @Test
    void titleModeMatchesOnlyTheTwoTitleFields() throws IOException {
        repository.search("batman", SearchMode.TITLE, 1, 25, noFilters(), null);

        assertThat(captured.index()).containsExactly(INDEX);
        assertThat(captured.from()).isZero();
        assertThat(captured.size()).isEqualTo(25);
        assertThat(captured.query().bool().must()).hasSize(1);
        assertThat(captured.query().bool().must().get(0).multiMatch().query()).isEqualTo("batman");
        assertThat(captured.query().bool().must().get(0).multiMatch().fields())
                .containsExactly("title", "original_title");
    }

    @Test
    void bm25ModeBoostsTitleOverOriginalTitleOverTheLongFields() throws IOException {
        repository.search("batman", SearchMode.BM25, 1, 50, noFilters(), null);

        assertThat(captured.query().bool().must().get(0).multiMatch().fields())
                .containsExactly("title^3", "original_title^2", "overview", "plot");
    }

    @Test
    void elserModeRunsASemanticQueryAgainstThePlotElserField() throws IOException {
        repository.search("a caped vigilante", SearchMode.ELSER, 1, 50, noFilters(), null);

        assertThat(captured.query().bool().must().get(0).semantic().field()).isEqualTo("plot_elser");
        assertThat(captured.query().bool().must().get(0).semantic().query()).isEqualTo("a caped vigilante");
    }

    @Test
    void e5ModeRunsASemanticQueryAgainstThePlotE5Field() throws IOException {
        repository.search("a caped vigilante", SearchMode.E5, 1, 50, noFilters(), null);

        assertThat(captured.query().bool().must().get(0).semantic().field()).isEqualTo("plot_e5");
    }

    @Test
    void hybridModeCombinesBm25AndElserThroughRrf() throws IOException {
        repository.search("batman", SearchMode.HYBRID, 1, 50, noFilters(), null);

        assertThat(captured.query()).isNull();
        assertThat(captured.retriever()).isNotNull();
        assertThat(captured.retriever().rrf().rankConstant()).isEqualTo(60);
        assertThat(captured.retriever().rrf().rankWindowSize()).isEqualTo(100);

        List<RRFRetrieverEntry> legs = captured.retriever().rrf().retrievers();
        assertThat(legs).hasSize(2);
        assertThat(legs.get(0).retriever().standard().query().multiMatch().fields())
                .containsExactly("title^3", "original_title^2", "overview", "plot");
        assertThat(legs.get(1).retriever().standard().query().semantic().field()).isEqualTo("plot_elser");
    }

    @Test
    void hybridGrowsTheRankWindowWhenPagingDeeperThanTheDefault() throws IOException {
        // RRF requires from + size <= rank_window_size.
        repository.search("batman", SearchMode.HYBRID, 5, 100, noFilters(), null);

        assertThat(captured.from()).isEqualTo(400);
        assertThat(captured.retriever().rrf().rankWindowSize()).isEqualTo(500);
    }

    @Test
    void hybridFallsBackToBm25WhenAnAttributeSortIsRequested() throws IOException {
        // RRF cannot sort, so the request degrades — and says that it did.
        MovieRepository.SearchResult result =
                repository.search("batman", SearchMode.HYBRID, 1, 50, noFilters(), "RATING");

        assertThat(result.effectiveMode()).isEqualTo(SearchMode.BM25);
        assertThat(captured.retriever()).isNull();
        assertThat(captured.query().bool().must().get(0).multiMatch().fields())
                .containsExactly("title^3", "original_title^2", "overview", "plot");
        assertThat(captured.sort()).hasSize(1);
    }

    @Test
    void hybridWithoutSortReportsHybridAsTheEffectiveMode() throws IOException {
        assertThat(repository.search("batman", SearchMode.HYBRID, 1, 50, noFilters(), null).effectiveMode())
                .isEqualTo(SearchMode.HYBRID);
    }

    @Test
    void reportsTheRequestedModeAsEffectiveForNonDegradingModes() throws IOException {
        assertThat(repository.search("q", SearchMode.TITLE, 1, 50, noFilters(), null).effectiveMode())
                .isEqualTo(SearchMode.TITLE);
        assertThat(repository.search("q", SearchMode.ELSER, 1, 50, noFilters(), null).effectiveMode())
                .isEqualTo(SearchMode.ELSER);
        assertThat(repository.search("q", SearchMode.E5, 1, 50, noFilters(), null).effectiveMode())
                .isEqualTo(SearchMode.E5);
    }

    // ---------------------------------------------------------------- ELSER + rerank

    @Test
    void elserJinaWrapsTheSemanticRetrieverInATextSimilarityReranker() throws IOException {
        repository.search("a caped vigilante", SearchMode.ELSER_JINA, 1, 25, noFilters(), null);

        assertThat(captured.query()).isNull();
        assertThat(captured.retriever().isTextSimilarityReranker()).isTrue();

        var reranker = captured.retriever().textSimilarityReranker();
        assertThat(reranker.retriever().standard().query().semantic().field()).isEqualTo("plot_elser");
        assertThat(reranker.retriever().standard().query().semantic().query()).isEqualTo("a caped vigilante");
    }

    @Test
    void elserJinaRerankThePlotFieldAgainstTheQueryText() throws IOException {
        repository.search("a caped vigilante", SearchMode.ELSER_JINA, 1, 25, noFilters(), null);

        var reranker = captured.retriever().textSimilarityReranker();
        assertThat(reranker.field()).isEqualTo("plot");
        assertThat(reranker.inferenceText()).isEqualTo("a caped vigilante");
    }

    @Test
    void elserJinaUsesTheConfiguredInferenceIdAndWindow() throws IOException {
        repository.search("batman", SearchMode.ELSER_JINA, 1, 25, noFilters(), null);

        var reranker = captured.retriever().textSimilarityReranker();
        assertThat(reranker.inferenceId()).isEqualTo(RERANK_ID);
        // Explicit: the retriever's own default is 10, which would silently cap a 25-per-page UI.
        assertThat(reranker.rankWindowSize()).isEqualTo(RERANK_WINDOW);
    }

    @Test
    void elserJinaKeepsTheRankWindowFixedWhenPagingDeeper() throws IOException {
        // Unlike RRF, the window is not grown to cover from+size: the reranker is listwise, so
        // widening it per page would change the candidate set and reshuffle the ranking between
        // pages. The service clamps the page instead.
        repository.search("batman", SearchMode.ELSER_JINA, 2, 25, noFilters(), null);

        assertThat(captured.from()).isEqualTo(25);
        assertThat(captured.retriever().textSimilarityReranker().rankWindowSize()).isEqualTo(RERANK_WINDOW);
    }

    @Test
    void elserJinaAppliesFiltersToTheInnerRetriever() throws IOException {
        // The 8.17 client cannot express the reranker's own filter, so they must ride along
        // on the retriever it wraps.
        repository.search("batman", SearchMode.ELSER_JINA, 1, 25,
                new MovieRepository.SearchFilters(List.of("Action"), 1999, "R"), null);

        assertThat(captured.retriever().textSimilarityReranker().retriever().standard().filter()).hasSize(3);
    }

    @Test
    void elserJinaFallsBackToBm25WhenAnAttributeSortIsRequested() throws IOException {
        MovieRepository.SearchResult result =
                repository.search("batman", SearchMode.ELSER_JINA, 1, 25, noFilters(), "RATING");

        assertThat(result.effectiveMode()).isEqualTo(SearchMode.BM25);
        assertThat(captured.retriever()).isNull();
        assertThat(captured.sort()).hasSize(1);
    }

    @Test
    void elserJinaWithoutSortReportsItselfAsTheEffectiveMode() throws IOException {
        assertThat(repository.search("batman", SearchMode.ELSER_JINA, 1, 25, noFilters(), null).effectiveMode())
                .isEqualTo(SearchMode.ELSER_JINA);
    }

    @Test
    void onlyRetrieverBasedModesDegradeWhenSortingIsRequested() {
        assertThat(MovieRepository.degradesToBm25(SearchMode.HYBRID, "RATING")).isTrue();
        assertThat(MovieRepository.degradesToBm25(SearchMode.ELSER_JINA, "YEAR")).isTrue();
        assertThat(MovieRepository.degradesToBm25(SearchMode.ELSER_JINA, null)).isFalse();
        assertThat(MovieRepository.degradesToBm25(SearchMode.ELSER_JINA, "  ")).isFalse();
        assertThat(MovieRepository.degradesToBm25(SearchMode.ELSER, "RATING")).isFalse();
        assertThat(MovieRepository.degradesToBm25(SearchMode.TITLE, "RATING")).isFalse();
    }

    @Test
    void translatesThePageNumberIntoAFromOffset() throws IOException {
        repository.search("batman", SearchMode.TITLE, 3, 25, noFilters(), null);

        assertThat(captured.from()).isEqualTo(50);
        assertThat(captured.size()).isEqualTo(25);
    }

    @Test
    void appliesFiltersInsideTheBoolFilterClause() throws IOException {
        repository.search("batman", SearchMode.TITLE, 1, 50,
                new MovieRepository.SearchFilters(List.of("Action"), 1999, "PG-13"), null);

        assertThat(captured.query().bool().filter()).hasSize(3);
    }

    @Test
    void appliesFiltersToBothLegsOfTheHybridRetriever() throws IOException {
        repository.search("batman", SearchMode.HYBRID, 1, 50,
                new MovieRepository.SearchFilters(List.of("Action"), null, null), null);

        List<RRFRetrieverEntry> legs = captured.retriever().rrf().retrievers();
        assertThat(legs.get(0).retriever().standard().filter()).hasSize(1);
        assertThat(legs.get(1).retriever().standard().filter()).hasSize(1);
    }

    // ---------------------------------------------------------------- result mapping

    @Test
    void mapsHitsAndTotalOntoTheSearchResult() throws IOException {
        stubResponse(responseWith(movie("155"), movie("278")));

        MovieRepository.SearchResult result = repository.search("batman", SearchMode.TITLE, 1, 50, noFilters(), null);

        assertThat(result.movies()).extracting(Movie::getId).containsExactly("155", "278");
        assertThat(result.totalHits()).isEqualTo(2);
    }

    @Test
    void treatsAnAbsentHitTotalAsZero() throws IOException {
        stubResponse(SearchResponse.of(r -> r
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(List.of()))));

        assertThat(repository.search("batman", SearchMode.TITLE, 1, 50, noFilters(), null).totalHits()).isZero();
    }

    // ---------------------------------------------------------------- findById

    @Test
    void findsAMovieByItsDomainIdField() throws IOException {
        stubResponse(responseWith(movie("155")));

        Optional<Movie> found = repository.findById("155");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("155");
        assertThat(captured.query().term().field()).isEqualTo("id");
        assertThat(captured.query().term().value().stringValue()).isEqualTo("155");
        // Two hits are requested so a duplicated id is visible rather than silent.
        assertThat(captured.size()).isEqualTo(2);
    }

    @Test
    void returnsEmptyWhenNoDocumentCarriesTheId() throws IOException {
        assertThat(repository.findById("nope")).isEmpty();
    }

    @Test
    void returnsTheFirstHitWhenTheIdIsDuplicated() throws IOException {
        stubResponse(responseWith(movie("155"), movie("155")));

        assertThat(repository.findById("155")).isPresent();
    }

    // ---------------------------------------------------------------- filters

    @Test
    void buildsNoFiltersWhenFiltersAreNull() {
        assertThat(repository.buildFilters(null)).isEmpty();
    }

    @Test
    void buildsATermsFilterForGenres() {
        List<Query> filters = repository.buildFilters(new MovieRepository.SearchFilters(List.of("Action", "Drama"), null, null));

        assertThat(filters).hasSize(1);
        assertThat(filters.get(0).isTerms()).isTrue();
        assertThat(filters.get(0).terms().field()).isEqualTo("genres");
    }

    @Test
    void canonicalizesGenreCasingBecauseTermsIsNotAnalyzed() {
        List<Query> filters = repository.buildFilters(new MovieRepository.SearchFilters(List.of("action", "SCIENCE FICTION"), null, null));

        assertThat(filters).hasSize(1);
        assertThat(filters.get(0).terms().terms().value())
                .extracting(value -> value.stringValue())
                .containsExactly("Action", "Science Fiction");
    }

    @Test
    void ignoresGenreListsThatContainOnlyBlanks() {
        // An empty terms clause would match nothing at all.
        assertThat(repository.buildFilters(new MovieRepository.SearchFilters(List.of("  ", ""), null, null))).isEmpty();
    }

    @Test
    void buildsATermFilterForRating() {
        List<Query> filters = repository.buildFilters(new MovieRepository.SearchFilters(null, null, "PG-13"));

        assertThat(filters).hasSize(1);
        assertThat(filters.get(0).isTerm()).isTrue();
        assertThat(filters.get(0).term().field()).isEqualTo("rating");
    }

    @Test
    void ignoresABlankRating() {
        assertThat(repository.buildFilters(new MovieRepository.SearchFilters(null, null, "  "))).isEmpty();
    }

    @Test
    void buildsARangeFilterForAPlausibleYear() {
        List<Query> filters = repository.buildFilters(new MovieRepository.SearchFilters(null, 1999, null));

        assertThat(filters).hasSize(1);
        assertThat(filters.get(0).isRange()).isTrue();
    }

    @Test
    void ignoresImplausibleYears() {
        List<Query> tooOld = repository.buildFilters(new MovieRepository.SearchFilters(null, 1800, null));
        List<Query> tooFarInTheFuture = repository.buildFilters(new MovieRepository.SearchFilters(null, Year.now().getValue() + 50, null));

        assertThat(tooOld).isEmpty();
        assertThat(tooFarInTheFuture).isEmpty();
    }

    @Test
    void combinesAllFiltersWhenAllArePresent() {
        List<Query> filters = repository.buildFilters(new MovieRepository.SearchFilters(List.of("Action"), 2020, "R"));

        assertThat(filters).hasSize(3);
    }

    // ---------------------------------------------------------------- sort

    @Test
    void buildsNoSortWhenSortIsBlank() {
        assertThat(repository.buildSort(null)).isEmpty();
        assertThat(repository.buildSort("")).isEmpty();
        assertThat(repository.buildSort("unknown")).isEmpty();
    }

    @Test
    void buildsDescendingRatingSort() {
        List<SortOptions> sort = repository.buildSort("rating");

        assertThat(sort).hasSize(1);
        assertThat(sort.get(0).field().field()).isEqualTo("vote_average");
        assertThat(sort.get(0).field().order()).isEqualTo(SortOrder.Desc);
    }

    @Test
    void buildsDescendingYearSort() {
        List<SortOptions> sort = repository.buildSort("YEAR");

        assertThat(sort).hasSize(1);
        assertThat(sort.get(0).field().field()).isEqualTo("release_date");
        assertThat(sort.get(0).field().order()).isEqualTo(SortOrder.Desc);
    }
}
