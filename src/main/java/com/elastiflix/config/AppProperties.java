package com.elastiflix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-level settings, bound immutably from the {@code app.*} prefix.
 *
 * @param pageSize          default number of results per search page
 * @param tmdbImageBase     base URL used to resolve poster image paths returned by TMDB
 * @param tmdbImageBaseLarge base URL used to resolve backdrop (large) image paths
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(int pageSize, String tmdbImageBase, String tmdbImageBaseLarge) {

    /** Smallest number of results per page that callers may request. */
    public static final int MIN_PAGE_SIZE = 1;

    /** Largest number of results per page that callers may request; protects Elasticsearch from oversized requests. */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * The page size a request will actually be served with.
     *
     * <p>Lives here rather than in the service because the search page has to render its
     * "results per page" picker before any search runs — and a picker that disagrees with the
     * size the server would use is worse than no picker at all.
     *
     * @param requested the size asked for, or {@code 0} when the request omitted it
     * @return {@code requested} when it is within {@value #MIN_PAGE_SIZE}–{@value #MAX_PAGE_SIZE},
     *         otherwise the configured {@link #pageSize()}
     */
    public int effectivePageSize(int requested) {
        return requested < MIN_PAGE_SIZE || requested > MAX_PAGE_SIZE ? pageSize : requested;
    }
}
