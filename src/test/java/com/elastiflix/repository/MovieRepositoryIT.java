package com.elastiflix.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.elastiflix.config.ElasticsearchProperties;
import com.elastiflix.config.RerankProperties;
import com.elastiflix.exception.ElasticsearchErrors;
import com.elastiflix.model.Movie;
import com.elastiflix.model.SearchMode;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the repository against a real Elasticsearch, which is the only way to prove
 * the cluster actually accepts the query shapes {@code MovieRepositoryTest} asserts.
 *
 * <p>Covers the modes that need no machine learning: TITLE, BM25, filters, sort,
 * pagination and {@code findById}. ELSER, E5 and HYBRID require deployed inference
 * endpoints, and their absence is already covered by the graceful-degradation tests.
 *
 * <p>Named {@code *IT} so it runs at {@code mvn verify}, not {@code mvn test}, and
 * skips itself when Docker is unavailable.
 */
class MovieRepositoryIT {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.5.0");

    private static final String INDEX = "elastiflix-movies";

    /**
     * An inference endpoint that deliberately does not exist. Reranking needs a deployed
     * model (and, for a third-party provider, a paid key), neither of which belongs in CI —
     * but pointing at a missing endpoint still proves the cluster <em>parsed</em> our
     * reranker query, which is the part a client upgrade would break.
     */
    private static final String MISSING_RERANK_ID = "no-such-rerank-endpoint";

    private static ElasticsearchContainer container;
    private static Rest5ClientTransport transport;
    private static MovieRepository repository;

    /**
     * Why Docker could not be used, or {@code null} when it can.
     *
     * <p>Captured rather than reduced to a boolean so a skip states its cause: a silently
     * skipped integration test is indistinguishable from a passing one in the build log.
     */
    private static String dockerUnavailableReason() {
        try {
            DockerClientFactory.instance().client();
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    @BeforeAll
    static void startCluster() throws IOException {
        String unavailable = dockerUnavailableReason();
        if (unavailable != null) {
            // Opt-in strictness: CI can set -Delastiflix.it.requireDocker=true so a missing
            // Docker fails the build instead of quietly skipping the only real-cluster test.
            if (Boolean.getBoolean("elastiflix.it.requireDocker")) {
                throw new IllegalStateException("Docker is required but unavailable — " + unavailable);
            }
            assumeTrue(false, "Skipping the Elasticsearch integration test — " + unavailable);
        }

        container = new ElasticsearchContainer(IMAGE)
                .withEnv("discovery.type", "single-node")
                // Security off keeps the test to plain HTTP with no credentials: this
                // exercises the query DSL, not the authentication path.
                .withEnv("xpack.security.enabled", "false")
                // The default basic license rejects text_similarity_reranker outright
                // ("current license is non-compliant"), which would mask the
                // missing-endpoint errors the rerank tests assert on. Trial unlocks the
                // feature so the cluster gets far enough to report the real cause.
                .withEnv("xpack.license.self_generated.type", "trial")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withStartupTimeout(Duration.ofMinutes(3));
        container.start();

        // getHttpHostAddress() returns a bare host:port (security is disabled), but both
        // Rest5Client and ElasticsearchProperties require an explicit scheme.
        String clusterUrl = "http://" + container.getHttpHostAddress();

        transport = new Rest5ClientTransport(
                Rest5Client.builder(java.net.URI.create(clusterUrl)).build(),
                new JacksonJsonpMapper());
        ElasticsearchClient client = new ElasticsearchClient(transport);

        createIndex(client);
        indexFixtures(client);

        repository = new MovieRepository(
                client,
                new ElasticsearchProperties(clusterUrl, "unused", INDEX, false,
                        Duration.ofSeconds(5), Duration.ofSeconds(30)),
                // No rerank endpoint exists in this container; the reranked test below
                // asserts how the cluster rejects it, which is the point.
                new RerankProperties(MISSING_RERANK_ID, 50));
    }

    @AfterAll
    static void stopCluster() throws IOException {
        if (transport != null) {
            transport.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    /**
     * An explicit mapping, because dynamic mapping would make {@code genres} a
     * {@code text} field and the repository's {@code terms} filter would then never
     * match a capitalised genre.
     */
    private static void createIndex(ElasticsearchClient client) throws IOException {
        client.indices().create(request -> request
                .index(INDEX)
                .mappings(mappings -> mappings
                        .properties("id", property -> property.keyword(keyword -> keyword))
                        .properties("title", property -> property.text(text -> text))
                        .properties("original_title", property -> property.text(text -> text))
                        .properties("overview", property -> property.text(text -> text))
                        .properties("plot", property -> property.text(text -> text))
                        .properties("genres", property -> property.keyword(keyword -> keyword))
                        .properties("rating", property -> property.keyword(keyword -> keyword))
                        .properties("release_date", property -> property.date(date -> date))
                        .properties("vote_average", property -> property.double_(number -> number))));
    }

    /**
     * Four documents chosen so each assertion below can be exact:
     * <ul>
     *   <li>every plot contains {@code film}, giving the tests a term that matches all four
     *       without relying on a match-all query;</li>
     *   <li>{@code batman} appears in one <em>title</em> (272) and one <em>plot</em> (155), which
     *       is what separates TITLE from BM25 and proves the {@code title^3} boost;</li>
     *   <li>{@code gotham} appears in exactly one plot and in no title, as a bare token — the
     *       standard analyzer would turn {@code Gotham's} into {@code gotham's}, which
     *       {@code gotham} does not match.</li>
     * </ul>
     */
    private static void indexFixtures(ElasticsearchClient client) throws IOException {
        index(client, movie("155", "The Dark Knight", "Batman raises the stakes in his war on crime. A landmark film.",
                List.of("Action", "Crime"), "PG-13", "2008-07-16", 8.5));
        index(client, movie("272", "Batman Begins", "A young Bruce Wayne becomes the protector of Gotham. A great film.",
                List.of("Action", "Drama"), "PG-13", "2005-06-10", 7.7));
        index(client, movie("13", "Forrest Gump", "A man with a low IQ recounts his extraordinary life. A beloved film.",
                List.of("Comedy", "Drama"), "PG-13", "1994-07-06", 8.5));
        index(client, movie("598", "City of God", "Two boys grow up in a violent Rio neighbourhood. A gritty film.",
                List.of("Crime", "Drama"), "R", "2002-02-05", 8.4));
    }

    /** A term present in every fixture plot, used where a test needs the whole corpus back. */
    private static final String MATCHES_EVERYTHING = "film";

    private static Movie movie(String id, String title, String plot, List<String> genres,
                               String rating, String releaseDate, double voteAverage) {
        Movie movie = new Movie();
        movie.setId(id);
        movie.setTitle(title);
        movie.setOriginalTitle(title);
        movie.setOverview(plot);
        movie.setPlot(plot);
        movie.setGenres(genres);
        movie.setRating(rating);
        movie.setReleaseDate(releaseDate);
        movie.setVoteAverage(voteAverage);
        return movie;
    }

    private static void index(ElasticsearchClient client, Movie movie) throws IOException {
        client.index(request -> request
                .index(INDEX)
                .id(movie.getId())
                .document(movie)
                .refresh(Refresh.True));
    }

    private static MovieRepository.SearchFilters noFilters() {
        return new MovieRepository.SearchFilters(null, null, null);
    }

    // ---------------------------------------------------------------- tests

    @Test
    void titleModeFindsMoviesByTitle() throws IOException {
        // "Batman" is in 272's title and in 155's plot, so a title-scoped search returns
        // only the former — which is the whole point of the mode.
        MovieRepository.SearchResult result = repository.search("batman", SearchMode.TITLE, 1, 10, noFilters(), null);

        assertThat(result.movies()).extracting(Movie::getId).containsExactly("272");
        assertThat(result.totalHits()).isEqualTo(1);
        assertThat(result.effectiveMode()).isEqualTo(SearchMode.TITLE);
    }

    @Test
    void titleModeIgnoresThePlotText() throws IOException {
        // "Gotham" appears in a plot and in no title, so a title-only search must miss it.
        assertThat(repository.search("gotham", SearchMode.TITLE, 1, 10, noFilters(), null).movies()).isEmpty();
    }

    @Test
    void titleModeAlsoMatchesPartOfALongerTitle() throws IOException {
        assertThat(repository.search("knight", SearchMode.TITLE, 1, 10, noFilters(), null).movies())
                .extracting(Movie::getId).containsExactly("155");
    }

    @Test
    void bm25ModeAlsoSearchesTheLongFields() throws IOException {
        MovieRepository.SearchResult result = repository.search("gotham", SearchMode.BM25, 1, 10, noFilters(), null);

        assertThat(result.movies()).extracting(Movie::getId).containsExactly("272");
    }

    @Test
    void bm25RanksATitleMatchAboveAPlotMatch() throws IOException {
        // title carries a ^3 boost, so the movie named "Batman Begins" must outrank
        // one that merely mentions Batman in its plot.
        MovieRepository.SearchResult result = repository.search("batman", SearchMode.BM25, 1, 10, noFilters(), null);

        assertThat(result.movies()).extracting(Movie::getId).first().isEqualTo("272");
    }

    @Test
    void filtersByGenreRegardlessOfTheCasingTheUserTyped() throws IOException {
        // "crime" lowercase must still match the indexed keyword "Crime".
        MovieRepository.SearchResult result = repository.search(MATCHES_EVERYTHING, SearchMode.BM25, 1, 10,
                new MovieRepository.SearchFilters(List.of("crime"), null, null), null);

        assertThat(result.movies()).extracting(Movie::getId).containsExactlyInAnyOrder("155", "598");
    }

    @Test
    void filtersByRating() throws IOException {
        MovieRepository.SearchResult result = repository.search(MATCHES_EVERYTHING, SearchMode.BM25, 1, 10,
                new MovieRepository.SearchFilters(null, null, "R"), null);

        assertThat(result.movies()).extracting(Movie::getId).containsExactly("598");
    }

    @Test
    void filtersByReleaseYear() throws IOException {
        MovieRepository.SearchResult result = repository.search(MATCHES_EVERYTHING, SearchMode.BM25, 1, 10,
                new MovieRepository.SearchFilters(null, 1994, null), null);

        assertThat(result.movies()).extracting(Movie::getId).containsExactly("13");
    }

    @Test
    void sortsByRatingDescending() throws IOException {
        MovieRepository.SearchResult result =
                repository.search(MATCHES_EVERYTHING, SearchMode.BM25, 1, 10, noFilters(), "RATING");

        assertThat(result.movies()).hasSize(4);
        assertThat(result.movies()).extracting(Movie::getVoteAverage).isSortedAccordingTo(
                (left, right) -> Double.compare(right, left));
    }

    @Test
    void sortsByReleaseDateDescending() throws IOException {
        MovieRepository.SearchResult result =
                repository.search(MATCHES_EVERYTHING, SearchMode.BM25, 1, 10, noFilters(), "YEAR");

        assertThat(result.movies()).extracting(Movie::getId).containsExactly("155", "272", "598", "13");
    }

    @Test
    void paginatesWithoutOverlapping() throws IOException {
        MovieRepository.SearchResult first =
                repository.search(MATCHES_EVERYTHING, SearchMode.BM25, 1, 2, noFilters(), "YEAR");
        MovieRepository.SearchResult second =
                repository.search(MATCHES_EVERYTHING, SearchMode.BM25, 2, 2, noFilters(), "YEAR");

        assertThat(first.movies()).hasSize(2);
        assertThat(second.movies()).hasSize(2);
        assertThat(first.movies()).extracting(Movie::getId)
                .doesNotContainAnyElementsOf(second.movies().stream().map(Movie::getId).toList());
    }

    @Test
    void elserJinaProducesAQueryStructureTheClusterAccepts() {
        // The reranked mode cannot run end to end here: it needs both ELSER and a rerank
        // endpoint. What we can prove is that a real cluster understands the retriever we build — so
        // assert the cluster complains about the *missing endpoint*, not about the syntax.
        // A parsing_exception here would mean the generated DSL is malformed.
        assertThatThrownBy(() -> repository.search("a caped vigilante", SearchMode.ELSER_JINA, 1, 10, noFilters(), null))
                .isInstanceOf(ElasticsearchException.class)
                .satisfies(thrown -> {
                    ElasticsearchException failure = (ElasticsearchException) thrown;

                    // Every cause in the tree, flattened the same way MovieService reads it.
                    // The top-level message must NOT be trusted here: a retriever reports
                    // "[text_similarity_reranker] search failed - ... attached as suppressed
                    // exceptions" and hides the real cause underneath, which is exactly the
                    // trap this assertion used to fall into.
                    List<String> causes = ElasticsearchErrors.flatten(failure.error()).stream()
                            .map(cause -> (cause.type() + " " + cause.reason()).toLowerCase(Locale.ROOT))
                            .toList();

                    assertThat(causes)
                            .as("the cluster must reject the endpoint, not the query shape")
                            .noneMatch(cause -> cause.contains("parsing_exception")
                                    || cause.contains("x_content_parse_exception")
                                    || cause.contains("illegal_argument_exception"));

                    assertThat(causes)
                            .as("some cause must name the missing rerank endpoint")
                            .anyMatch(cause -> cause.contains("resource_not_found")
                                    || cause.contains("inference endpoint")
                                    || cause.contains(MISSING_RERANK_ID));
                });
    }

    @Test
    void aMissingRerankEndpointIsRecognisedDespiteTheRetrieverWrapper() {
        // The regression that matters: MovieService must classify this as a missing endpoint.
        // Reading ElasticsearchException.getMessage() alone reports a bare status_exception and
        // the user is told "search is unavailable" instead of which endpoint to deploy.
        assertThatThrownBy(() -> repository.search("a caped vigilante", SearchMode.ELSER_JINA, 1, 10, noFilters(), null))
                .isInstanceOfSatisfying(ElasticsearchException.class, failure ->
                        assertThat(ElasticsearchErrors.mentionsMissingInferenceEndpoint(failure)).isTrue());
    }

    @Test
    void elserJinaStillDegradesToBm25AgainstARealCluster() throws IOException {
        // With a sort there is no reranking, so this must succeed even though the rerank
        // endpoint is missing — the degradation path has to hold on a real cluster too.
        MovieRepository.SearchResult result =
                repository.search(MATCHES_EVERYTHING, SearchMode.ELSER_JINA, 1, 10, noFilters(), "YEAR");

        assertThat(result.effectiveMode()).isEqualTo(SearchMode.BM25);
        assertThat(result.movies()).extracting(Movie::getId).containsExactly("155", "272", "598", "13");
    }

    @Test
    void findsAMovieByItsDomainId() throws IOException {
        Optional<Movie> found = repository.findById("155");

        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("The Dark Knight");
    }

    @Test
    void returnsEmptyForAnUnknownId() throws IOException {
        assertThat(repository.findById("does-not-exist")).isEmpty();
    }
}
