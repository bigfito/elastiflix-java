package com.elastiflix.controller.web;

import com.elastiflix.config.AppProperties;
import com.elastiflix.exception.InferenceEndpointMissingException;
import com.elastiflix.exception.RerankUnavailableException;
import com.elastiflix.exception.SearchUnavailableException;
import com.elastiflix.model.Movie;
import com.elastiflix.model.MovieSearchPage;
import com.elastiflix.service.MovieService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(SearchController.class)
class SearchControllerTest {

    private static final int DEFAULT_PAGE_SIZE = 50;

    @TestConfiguration
    static class TestConfig {
        @Bean
        AppProperties appProperties() {
            return new AppProperties(DEFAULT_PAGE_SIZE,
                    "https://image.tmdb.org/t/p/w500", "https://image.tmdb.org/t/p/w1280");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MovieService movieService;

    private static Movie aMovie() {
        Movie movie = new Movie();
        movie.setId("155");
        movie.setTitle("The Dark Knight");
        movie.setPosterPath("https://image.tmdb.org/t/p/w500/poster.jpg");
        movie.setVoteAverage(8.5);
        movie.setReleaseDate("2008-07-16");
        movie.setGenres(List.of("Action", "Crime"));
        movie.setRating("PG-13");
        movie.setPlot("Batman raises the stakes in his war on crime.");
        movie.setCast(List.of("Christian Bale", "Heath Ledger", "Aaron Eckhart",
                "Michael Caine", "Maggie Gyllenhaal", "Gary Oldman"));
        return movie;
    }

    private static MovieSearchPage page(String mode, String effectiveMode) {
        return MovieSearchPage.of(List.of(aMovie()), 1, 1, DEFAULT_PAGE_SIZE, "batman", mode, effectiveMode,
                MovieSearchPage.MAX_RESULT_WINDOW);
    }

    @Test
    void rendersResultsForAValidQuery() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(page("TITLE", "TITLE"));

        mockMvc.perform(get("/search").param("q", "batman"))
                .andExpect(status().isOk())
                .andExpect(view().name("search"))
                .andExpect(model().attributeExists("results"))
                .andExpect(model().attribute("currentMode", "TITLE"));
    }

    @Test
    void normalizesUnknownModesToTitleInTheUi() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(MovieSearchPage.of(List.of(), 0, 1, DEFAULT_PAGE_SIZE, "batman", "TITLE", "TITLE",
                        MovieSearchPage.MAX_RESULT_WINDOW));

        mockMvc.perform(get("/search").param("q", "batman").param("mode", "not-a-mode"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("currentMode", "TITLE"));
    }

    @Test
    void doesNotSearchWhenTheQueryIsBlank() throws Exception {
        mockMvc.perform(get("/search"))
                .andExpect(status().isOk())
                .andExpect(view().name("search"));

        verify(movieService, never()).search(anyString(), anyString(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void doesNotSearchWhenTheQueryIsOnlyWhitespace() throws Exception {
        mockMvc.perform(get("/search").param("q", "   "))
                .andExpect(status().isOk());

        verify(movieService, never()).search(anyString(), anyString(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void showsAnInlineWarningWhenTheInferenceEndpointIsMissing() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new InferenceEndpointMissingException("elser", null));

        mockMvc.perform(get("/search").param("q", "batman").param("mode", "ELSER"))
                .andExpect(status().isOk())
                .andExpect(view().name("search"))
                .andExpect(model().attributeExists("searchError"));
    }

    @Test
    void showsAnInlineWarningWhenSearchIsUnavailable() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new SearchUnavailableException("Search is temporarily unavailable. Please try again later.", null));

        mockMvc.perform(get("/search").param("q", "batman"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("searchError"));
    }

    @Test
    void returnsBadRequestForANonNumericPage() throws Exception {
        // Regression: binding failures must surface as 400, not a 500 error page.
        mockMvc.perform(get("/search").param("q", "batman").param("page", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void defaultsToTheGridView() throws Exception {
        mockMvc.perform(get("/search"))
                .andExpect(model().attribute("view", "grid"));
    }

    @Test
    void normalizesAnUnrecognisedViewToGrid() throws Exception {
        // Otherwise neither the grid nor the list branch renders and results vanish.
        mockMvc.perform(get("/search").param("view", "sideways"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("view", "grid"));
    }

    @Test
    void keepsTheListViewWhenRequested() throws Exception {
        mockMvc.perform(get("/search").param("view", "LIST"))
                .andExpect(model().attribute("view", "list"));
    }

    @Test
    void usesTheConfiguredPageSizeWhenTheRequestOmitsIt() throws Exception {
        mockMvc.perform(get("/search"))
                .andExpect(model().attribute("pageSize", DEFAULT_PAGE_SIZE));
    }

    @Test
    void reportsThePageSizeThatWasActuallyServed() throws Exception {
        // The service clamps an out-of-range size; the picker and the pagination
        // links have to follow what came back, not what was asked for.
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(MovieSearchPage.of(List.of(), 0, 1, DEFAULT_PAGE_SIZE, "batman", "TITLE", "TITLE",
                        MovieSearchPage.MAX_RESULT_WINDOW));

        mockMvc.perform(get("/search").param("q", "batman").param("size", "9999"))
                .andExpect(model().attribute("pageSize", DEFAULT_PAGE_SIZE));
    }

    @Test
    void exposesTheClosedGenreSetForThePicker() throws Exception {
        mockMvc.perform(get("/search"))
                .andExpect(model().attributeExists("allGenres"));
    }

    @Test
    void canonicalizesSelectedGenresSoTheyStaySelected() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(page("TITLE", "TITLE"));

        mockMvc.perform(get("/search").param("q", "batman").param("genres", "action"))
                .andExpect(model().attribute("selectedGenres", List.of("Action")));
    }

    @Test
    void rendersTheDegradedModeNoticeWhenHybridFellBackToBm25() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(page("HYBRID", "BM25"));

        mockMvc.perform(get("/search").param("q", "batman").param("mode", "HYBRID").param("sort", "RATING"))
                .andExpect(status().isOk())
                .andExpect(view().name("search"));
    }

    @Test
    void rendersTheListViewLayoutWithResults() throws Exception {
        // Exercises the movie-list-item fragment, including its cast truncation.
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(page("TITLE", "TITLE"));

        mockMvc.perform(get("/search").param("q", "batman").param("view", "list"))
                .andExpect(status().isOk())
                .andExpect(view().name("search"));
    }

    @Test
    void rendersPaginationAndTheTruncationNoticeForALargeResultSet() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(MovieSearchPage.of(List.of(aMovie()), 50_000, 5, DEFAULT_PAGE_SIZE,
                        "batman", "TITLE", "TITLE", MovieSearchPage.MAX_RESULT_WINDOW));

        mockMvc.perform(get("/search").param("q", "batman").param("page", "5"))
                .andExpect(status().isOk())
                .andExpect(view().name("search"));
    }

    @Test
    void suggestsElserRatherThanBm25WhenTheRerankedModeIsMissingItsEndpoint() throws Exception {
        // A reranked search already has ELSER underneath it, so falling back to BM25 would
        // throw away semantic recall the user asked for.
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new InferenceEndpointMissingException("eis-jina-reranker", null));

        mockMvc.perform(get("/search").param("q", "batman").param("mode", "ELSER_JINA"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("searchError",
                        org.hamcrest.Matchers.containsString("Semantic (ELSER)")));
    }

    @Test
    void stillSuggestsBm25ForNonRerankedModes() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new InferenceEndpointMissingException("elser", null));

        mockMvc.perform(get("/search").param("q", "batman").param("mode", "ELSER"))
                .andExpect(model().attribute("searchError",
                        org.hamcrest.Matchers.containsString("BM25 (Keyword)")));
    }

    @Test
    void showsADistinctWarningWhenTheRerankProviderRefusesTheRequest() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new RerankUnavailableException("eis-jina-reranker", "authentication was refused", null));

        mockMvc.perform(get("/search").param("q", "batman").param("mode", "ELSER_JINA"))
                .andExpect(status().isOk())
                .andExpect(view().name("search"))
                .andExpect(model().attribute("searchError",
                        org.hamcrest.Matchers.containsString("credentials and quota")));
    }

    @Test
    void rendersTheRerankedModeResults() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(page("ELSER_JINA", "ELSER_JINA"));

        mockMvc.perform(get("/search").param("q", "batman").param("mode", "ELSER_JINA"))
                .andExpect(status().isOk())
                .andExpect(view().name("search"))
                .andExpect(model().attribute("currentMode", "ELSER_JINA"));
    }

    @Test
    void setsTheBaselineSecurityHeaders() throws Exception {
        mockMvc.perform(get("/search"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().exists("Permissions-Policy"));
    }

    @Test
    void allowsNoInlineScriptStyleOrThirdPartyOriginInTheCsp() throws Exception {
        // Regression guard for the vendored stylesheet: reintroducing a CDN <script> or an
        // inline on* handler means someone has to weaken this policy, and this test says so.
        mockMvc.perform(get("/search"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("script-src 'self'"),
                                org.hamcrest.Matchers.containsString("style-src 'self'"),
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unsafe-inline")),
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("cdn.tailwindcss.com")))));
    }

    @Test
    void rendersNoInlineEventHandlersOrScriptTags() throws Exception {
        // The CSP above would block them at runtime; this catches them at build time.
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(page("TITLE", "TITLE"));

        String html = mockMvc.perform(get("/search").param("q", "batman"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .doesNotContain("onchange=")
                .doesNotContain("onerror=")
                .doesNotContain("onkeypress=")
                .doesNotContain("cdn.tailwindcss.com");
        assertThat(html).contains("/css/elastiflix.css");
    }

    @Test
    void doesNotSendHstsOverPlainHttp() throws Exception {
        // Sending it from a dev server would pin localhost to https:// in the browser for a year.
        mockMvc.perform(get("/search"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void sendsHstsOverHttps() throws Exception {
        mockMvc.perform(get("/search").secure(true))
                .andExpect(header().string("Strict-Transport-Security",
                        org.hamcrest.Matchers.containsString("max-age=")));
    }

    // ---------------------------------------------------------------- error headings

    @Test
    void headsAMissingEndpointWarningWithTheInferenceTitle() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new InferenceEndpointMissingException("elser", null));

        mockMvc.perform(get("/search").param("q", "batman").param("mode", "ELSER"))
                .andExpect(model().attribute("searchErrorTitle", "Inference endpoint not available"));
    }

    @Test
    void headsARerankRefusalWithItsOwnTitle() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new RerankUnavailableException("eis-jina-reranker", "authentication was refused", null));

        mockMvc.perform(get("/search").param("q", "batman").param("mode", "ELSER_JINA"))
                .andExpect(model().attribute("searchErrorTitle", "Reranking unavailable"));
    }

    @Test
    void doesNotCallAClusterOutageAnInferenceProblem() throws Exception {
        // The heading used to be hardcoded to "Inference endpoint not available", which sent
        // users debugging ML endpoints when the cluster itself was down.
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new SearchUnavailableException("Could not reach Elasticsearch. Please try again later.", null));

        mockMvc.perform(get("/search").param("q", "batman"))
                .andExpect(model().attribute("searchErrorTitle", "Search unavailable"));
    }

    // ---------------------------------------------------------------- filters, title, size

    @Test
    void saysSoWhenAnImplausibleYearFilterIsDiscarded() throws Exception {
        // The repository refuses to send a filter that could never match; without this notice
        // the user sees a full unfiltered result set and no hint that their filter was dropped.
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(page("TITLE", "TITLE"));

        mockMvc.perform(get("/search").param("q", "batman").param("year", "1200"))
                .andExpect(model().attribute("filterNotice",
                        org.hamcrest.Matchers.containsString("1200")));
    }

    @Test
    void staysQuietAboutAPlausibleYearFilter() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(page("TITLE", "TITLE"));

        mockMvc.perform(get("/search").param("q", "batman").param("year", "1994"))
                .andExpect(model().attributeDoesNotExist("filterNotice"));
    }

    @Test
    void staysQuietWhenNoYearWasSubmitted() throws Exception {
        mockMvc.perform(get("/search"))
                .andExpect(model().attributeDoesNotExist("filterNotice"));
    }

    @Test
    void titlesThePageWithTheQuery() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(page("TITLE", "TITLE"));

        mockMvc.perform(get("/search").param("q", "batman"))
                .andExpect(model().attribute("pageTitle", "batman"));
    }

    @Test
    void titlesTheEmptySearchPageGenerically() throws Exception {
        mockMvc.perform(get("/search"))
                .andExpect(model().attribute("pageTitle", "Search"));
    }

    @Test
    void doesNotOfferAPageSizeTheServerWouldRefuse() throws Exception {
        // No query means no search runs to correct the picker, so the controller has to apply
        // the same clamp the service would; otherwise ?size=500 renders a picker showing 25.
        mockMvc.perform(get("/search").param("size", "500"))
                .andExpect(model().attribute("pageSize", DEFAULT_PAGE_SIZE));
    }

    @Test
    void keepsAValidPageSizeOnTheEmptySearchPage() throws Exception {
        mockMvc.perform(get("/search").param("size", "25"))
                .andExpect(model().attribute("pageSize", 25));
    }

    @Test
    void suppliesTheFooterYearToTheLayout() throws Exception {
        mockMvc.perform(get("/search"))
                .andExpect(model().attribute("currentYear", java.time.Year.now().getValue()));
    }
}
