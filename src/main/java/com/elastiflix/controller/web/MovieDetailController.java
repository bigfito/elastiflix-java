package com.elastiflix.controller.web;

import com.elastiflix.model.Movie;
import com.elastiflix.model.SearchMode;
import com.elastiflix.service.MovieService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

/**
 * Renders the movie detail page, preserving the originating search (query, mode,
 * page) so the "back to search" link returns the user to where they were.
 */
@Controller
public class MovieDetailController {

    private final MovieService movieService;

    public MovieDetailController(MovieService movieService) {
        this.movieService = movieService;
    }

    @GetMapping("/movies/{id}")
    public String detail(
            @PathVariable String id,
            @RequestParam(required = false) String backQuery,
            @RequestParam(required = false, defaultValue = "TITLE") String backMode,
            @RequestParam(required = false, defaultValue = "1") int backPage,
            Model model
    ) {
        Optional<Movie> movie = movieService.findById(id);
        if (movie.isEmpty()) {
            return "redirect:/?notFound=true";
        }
        model.addAttribute("movie", movie.get());
        model.addAttribute("pageTitle", movie.get().getTitle());
        model.addAttribute("backQuery", backQuery);
        // Normalized so the "back to results" link cannot carry a bogus mode onwards.
        model.addAttribute("backMode", SearchMode.fromString(backMode).name());
        model.addAttribute("backPage", backPage);
        return "movie-detail";
    }
}
