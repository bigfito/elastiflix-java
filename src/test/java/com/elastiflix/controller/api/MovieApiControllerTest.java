package com.elastiflix.controller.api;

import com.elastiflix.exception.SearchUnavailableException;
import com.elastiflix.model.Movie;
import com.elastiflix.model.MovieSearchPage;
import com.elastiflix.service.MovieService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MovieApiController.class)
class MovieApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MovieService movieService;

    private static Movie aMovie() {
        Movie movie = new Movie();
        movie.setId("155");
        movie.setTitle("The Dark Knight");
        movie.setVoteAverage(8.5);
        return movie;
    }

    @Test
    void returnsSearchResultsAsJson() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(MovieSearchPage.of(List.of(aMovie()), 1, 1, 50, "batman", "TITLE", "TITLE",
                        MovieSearchPage.MAX_RESULT_WINDOW));

        mockMvc.perform(get("/api/search").param("q", "batman"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(1))
                .andExpect(jsonPath("$.movies[0].title").value("The Dark Knight"));
    }

    @Test
    void reportsBothTheRequestedAndTheEffectiveMode() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(MovieSearchPage.of(List.of(), 0, 1, 50, "batman", "HYBRID", "BM25",
                        MovieSearchPage.MAX_RESULT_WINDOW));

        mockMvc.perform(get("/api/search").param("q", "batman").param("mode", "HYBRID").param("sort", "RATING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("HYBRID"))
                .andExpect(jsonPath("$.effectiveMode").value("BM25"));
    }

    @Test
    void tellsClientsWhenTheResultWindowTruncatedThePageCount() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(MovieSearchPage.of(List.of(), 50_000, 1, 50, "batman", "TITLE", "TITLE",
                        MovieSearchPage.MAX_RESULT_WINDOW));

        mockMvc.perform(get("/api/search").param("q", "batman"))
                .andExpect(jsonPath("$.windowLimited").value(true))
                .andExpect(jsonPath("$.totalPages").value(200));
    }

    @Test
    void returnsAProblemDetailWhenTheQueryIsBlank() throws Exception {
        mockMvc.perform(get("/api/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Query parameter 'q' must not be blank."));
    }

    @Test
    void returnsAProblemDetailWithErrorCodeWhenSearchIsUnavailable() throws Exception {
        when(movieService.search(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new SearchUnavailableException("Search is temporarily unavailable. Please try again later.", null));

        mockMvc.perform(get("/api/search").param("q", "batman"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SEARCH_UNAVAILABLE"));
    }

    @Test
    void returnsBadRequestForANonNumericPage() throws Exception {
        // Regression: binding failures must surface as 400, not a blanket 500.
        mockMvc.perform(get("/api/search").param("q", "batman").param("page", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsAMovieById() throws Exception {
        when(movieService.findById("155")).thenReturn(Optional.of(aMovie()));

        mockMvc.perform(get("/api/movies/155"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("The Dark Knight"))
                .andExpect(jsonPath("$.vote_average").value(8.5));
    }

    @Test
    void returnsNotFoundForAnUnknownMovie() throws Exception {
        when(movieService.findById("999")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/movies/999"))
                .andExpect(status().isNotFound());
    }
}
