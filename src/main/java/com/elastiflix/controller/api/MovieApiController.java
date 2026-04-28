package com.elastiflix.controller.api;

import com.elastiflix.model.Movie;
import com.elastiflix.model.SearchResponse;
import com.elastiflix.service.MovieService;
import com.elastiflix.repository.MovieRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api")
public class MovieApiController {

    private final MovieService movieService;

    public MovieApiController(MovieService movieService) {
        this.movieService = movieService;
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "TITLE") String mode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) List<String> genres,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String rating,
            @RequestParam(required = false) String sort
    ) throws IOException {
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        MovieRepository.SearchFilters filters = new MovieRepository.SearchFilters(genres, year, rating);
        return ResponseEntity.ok(movieService.search(q, mode, page, size, filters, sort));
    }

    @GetMapping("/movies/{id}")
    public ResponseEntity<Movie> getMovie(@PathVariable String id) throws IOException {
        return movieService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
