package com.elastiflix.controller.web;

import com.elastiflix.exception.SearchUnavailableException;
import com.elastiflix.model.Movie;
import com.elastiflix.service.MovieService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(MovieDetailController.class)
class MovieDetailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MovieService movieService;

    private static Movie aMovie() {
        Movie movie = new Movie();
        movie.setId("155");
        movie.setTitle("The Dark Knight");
        movie.setOverview("Batman raises the stakes in his war on crime.");
        movie.setPosterPath("https://image.tmdb.org/t/p/w500/poster.jpg");
        movie.setBackdropPath("https://image.tmdb.org/t/p/w1280/backdrop.jpg");
        movie.setVoteAverage(8.5);
        movie.setVoteCount(30000);
        movie.setReleaseDate("2008-07-16");
        movie.setRuntime(152);
        movie.setGenres(List.of("Action", "Crime"));
        movie.setCast(List.of("Christian Bale", "Heath Ledger"));
        return movie;
    }

    @Test
    void rendersTheDetailPageForAnExistingMovie() throws Exception {
        when(movieService.findById("155")).thenReturn(Optional.of(aMovie()));

        mockMvc.perform(get("/movies/155"))
                .andExpect(status().isOk())
                .andExpect(view().name("movie-detail"))
                .andExpect(model().attributeExists("movie"));
    }

    @Test
    void redirectsHomeWhenTheMovieDoesNotExist() throws Exception {
        when(movieService.findById("999")).thenReturn(Optional.empty());

        mockMvc.perform(get("/movies/999"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?notFound=true"));
    }

    @Test
    void normalizesTheBackModeSoTheReturnLinkCannotCarryABogusOne() throws Exception {
        when(movieService.findById("155")).thenReturn(Optional.of(aMovie()));

        mockMvc.perform(get("/movies/155").param("backQuery", "batman").param("backMode", "not-a-mode"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("backMode", "TITLE"));
    }

    @Test
    void titlesThePageWithTheMovieTitle() throws Exception {
        when(movieService.findById("155")).thenReturn(Optional.of(aMovie()));

        mockMvc.perform(get("/movies/155"))
                .andExpect(model().attribute("pageTitle", "The Dark Knight"));
    }

    @Test
    void rendersADetailPageForAMovieWithNoUsableReleaseDate() throws Exception {
        // Regression: the template used to slice the year out with substring(0, 4), which threw
        // on any document whose date was shorter than that and failed the whole page.
        Movie movie = aMovie();
        movie.setReleaseDate("20");

        when(movieService.findById("155")).thenReturn(Optional.of(movie));

        mockMvc.perform(get("/movies/155"))
                .andExpect(status().isOk())
                .andExpect(view().name("movie-detail"));
    }

    @Test
    void rendersTheErrorViewWhenElasticsearchIsUnavailable() throws Exception {
        // Also the only test that renders error.html end to end.
        when(movieService.findById("155"))
                .thenThrow(new SearchUnavailableException("Could not reach Elasticsearch. Please try again later.", null));

        mockMvc.perform(get("/movies/155"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("errorTitle", "Search Unavailable"));
    }
}
