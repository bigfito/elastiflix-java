package com.elastiflix.model;

import lombok.Data;

import java.util.List;

@Data
public class SearchResponse {

    private List<Movie> movies;
    private long totalHits;
    private int currentPage;
    private int pageSize;
    private int totalPages;
    private String query;
    private String mode;
    private List<String> availableGenres;
    private List<Integer> availableYears;

    public static SearchResponse of(List<Movie> movies, long totalHits, int currentPage, int pageSize, String query, String mode, List<String> availableGenres, List<Integer> availableYears) {
        SearchResponse r = new SearchResponse();
        r.movies = movies;
        r.totalHits = totalHits;
        r.currentPage = currentPage;
        r.pageSize = pageSize;
        r.totalPages = (int) Math.ceil((double) totalHits / pageSize);
        r.query = query;
        r.mode = mode;
        r.availableGenres = availableGenres;
        r.availableYears = availableYears;
        return r;
    }

    public int getFromIndex() {
        return (currentPage - 1) * pageSize + 1;
    }

    public int getToIndex() {
        return (int) Math.min((long) currentPage * pageSize, totalHits);
    }

    public boolean hasPreviousPage() {
        return currentPage > 1;
    }

    public boolean hasNextPage() {
        return currentPage < totalPages;
    }
}
