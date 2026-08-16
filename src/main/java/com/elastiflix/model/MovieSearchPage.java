package com.elastiflix.model;

import lombok.Data;

import java.util.List;

/**
 * A page of search results, plus the pagination metadata the results view needs
 * to render "showing X–Y of Z" and the previous/next controls.
 *
 * <p>Named {@code MovieSearchPage} rather than {@code SearchResponse} so it does
 * not collide with {@code co.elastic.clients.elasticsearch.core.SearchResponse},
 * which the repository works with.
 */
@Data
public class MovieSearchPage {

    /**
     * Elasticsearch's default {@code index.max_result_window}: a request whose
     * {@code from + size} exceeds this is rejected outright.
     *
     * <p>Lives here because both the page count published to the view and the
     * page clamp applied by {@code MovieService} have to agree on it — when they
     * disagree the UI offers pages the server will never serve.
     */
    public static final int MAX_RESULT_WINDOW = 10_000;

    private List<Movie> movies;
    private long totalHits;
    private int currentPage;
    private int pageSize;
    private int totalPages;
    private String query;

    /** The mode that was asked for. */
    private String mode;

    /**
     * The mode actually executed. Differs from {@link #mode} when a strategy had
     * to degrade — Hybrid falls back to BM25 as soon as an attribute sort is
     * requested, because RRF cannot sort.
     */
    private String effectiveMode;

    /**
     * {@code true} when more movies matched than can be paged through, so
     * {@link #totalPages} stops short of the full match count.
     */
    private boolean windowLimited;

    /**
     * How many of the matching movies are actually retrievable — the result window for
     * most modes, or the rerank window for a reranked one, never more than {@link #totalHits}.
     */
    private long reachableDocuments;

    /**
     * @param reachableDocuments the ceiling on retrievable documents for the mode that ran.
     *                           Reranked modes only score {@code rank_window_size} candidates,
     *                           so nothing beyond that can ever be returned.
     */
    public static MovieSearchPage of(List<Movie> movies, long totalHits, int currentPage, int pageSize,
                                     String query, String mode, String effectiveMode, int reachableDocuments) {
        MovieSearchPage page = new MovieSearchPage();
        page.movies = movies;
        page.totalHits = totalHits;
        page.currentPage = currentPage;
        page.pageSize = pageSize;
        page.query = query;
        page.mode = mode;
        page.effectiveMode = effectiveMode;
        page.reachableDocuments = Math.min(reachableDocuments, totalHits);

        int matchingPages = pageSize > 0 ? (int) Math.ceil((double) totalHits / pageSize) : 0;
        // At least one page: a window smaller than a single page still returns that page.
        int reachablePages = pageSize > 0 ? Math.max(1, reachableDocuments / pageSize) : 0;
        // Never advertise a page the window puts out of reach: the service clamps such a
        // request back down, which would otherwise serve page N while the address bar
        // still claims page N+500.
        page.totalPages = Math.min(matchingPages, reachablePages);
        page.windowLimited = matchingPages > reachablePages;
        return page;
    }

    public int getFromIndex() {
        return totalHits == 0 ? 0 : (currentPage - 1) * pageSize + 1;
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

    /**
     * How many numbered page links to show on each side of the current page.
     *
     * <p>The window used to be computed in the template with {@code T(java.lang.Math)} type
     * references, which are neither testable nor visible to the coverage report.
     */
    private static final int PAGE_LINK_RADIUS = 3;

    /** First page number in the numbered-link window. */
    public int getPageWindowStart() {
        return Math.max(1, currentPage - PAGE_LINK_RADIUS);
    }

    /** Last page number in the numbered-link window. */
    public int getPageWindowEnd() {
        return Math.min(totalPages, currentPage + PAGE_LINK_RADIUS);
    }
}
