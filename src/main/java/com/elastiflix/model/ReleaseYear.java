package com.elastiflix.model;

import java.time.Year;

/**
 * The range of release years the year filter accepts.
 *
 * <p>Shared rather than kept inside the query builder because two layers need the same answer:
 * the repository, which must not send Elasticsearch a filter that can never match, and the
 * search page, which has to tell the user their filter was ignored instead of silently
 * returning unfiltered results.
 */
public final class ReleaseYear {

    /** Earliest year accepted — the first commercial motion pictures. */
    public static final int MIN_YEAR = 1870;

    /** How far ahead of today a release year may sit, to allow for announced films. */
    public static final int MAX_YEARS_AHEAD = 5;

    private ReleaseYear() {
    }

    /** Whether {@code year} could plausibly be a film's release year. */
    public static boolean isPlausible(int year) {
        return year >= MIN_YEAR && year <= Year.now().getValue() + MAX_YEARS_AHEAD;
    }

    /** Null-tolerant form of {@link #isPlausible(int)}; an absent year is not implausible, just absent. */
    public static boolean isPlausible(Integer year) {
        return year == null || isPlausible(year.intValue());
    }
}
