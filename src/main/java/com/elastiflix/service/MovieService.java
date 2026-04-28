package com.elastiflix.service;

import com.elastiflix.config.AppProperties;
import com.elastiflix.model.Movie;
import com.elastiflix.model.SearchMode;
import com.elastiflix.model.SearchResponse;
import com.elastiflix.repository.MovieRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;

@Service
public class MovieService {

    private final MovieRepository repository;
    private final AppProperties props;

    public MovieService(MovieRepository repository, AppProperties props) {
        this.repository = repository;
        this.props = props;
    }

    public SearchResponse search(String query, String mode, int page, int size, MovieRepository.SearchFilters filters, String sort) throws IOException {
        SearchMode searchMode = SearchMode.fromString(mode);
        int safePage = Math.max(1, page);

        MovieRepository.SearchResult result = repository.search(query, searchMode, safePage, size, filters, sort);

        enrichPosterPaths(result.movies());

        return SearchResponse.of(result.movies(), result.totalHits(), safePage, size, query, searchMode.name());
    }

    public Optional<Movie> findById(String id) throws IOException {
        Optional<Movie> movie = repository.findById(id);
        movie.ifPresent(this::enrichPosterPath);
        return movie;
    }

    private void enrichPosterPaths(java.util.List<Movie> movies) {
        movies.forEach(this::enrichPosterPath);
    }

    private void enrichPosterPath(Movie movie) {
        if (movie.getPosterPath() != null && !movie.getPosterPath().startsWith("http")) {
            String path = movie.getPosterPath().startsWith("/")
                    ? movie.getPosterPath()
                    : "/" + movie.getPosterPath();
            movie.setPosterPath(props.tmdbImageBase + path);
        }
        if (movie.getBackdropPath() != null && !movie.getBackdropPath().startsWith("http")) {
            String path = movie.getBackdropPath().startsWith("/")
                    ? movie.getBackdropPath()
                    : "/" + movie.getBackdropPath();
            movie.setBackdropPath(props.tmdbImageBase.replace("w500", "w1280") + path);
        }
    }
}
