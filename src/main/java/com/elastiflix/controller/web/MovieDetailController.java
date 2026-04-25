package com.elastiflix.controller.web;

import com.elastiflix.model.Movie;
import com.elastiflix.service.MovieService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.Optional;

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
            @RequestParam(required = false, defaultValue = "BM25") String backMode,
            @RequestParam(required = false, defaultValue = "1") int backPage,
            Model model
    ) throws IOException {
        Optional<Movie> movie = movieService.findById(id);
        if (movie.isEmpty()) {
            return "redirect:/?notFound=true";
        }
        model.addAttribute("movie", movie.get());
        model.addAttribute("backQuery", backQuery);
        model.addAttribute("backMode", backMode);
        model.addAttribute("backPage", backPage);
        return "movie-detail";
    }
}
