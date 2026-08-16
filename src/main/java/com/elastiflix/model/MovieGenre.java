package com.elastiflix.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The closed set of TMDB genres stored in the {@code genres} keyword field.
 *
 * <p>The genre filter is an Elasticsearch {@code terms} query, which is not
 * analysed — it matches the indexed keyword byte for byte. Without normalisation
 * a user filtering on {@code action} rather than {@code Action} gets zero
 * results and no explanation, so incoming values are canonicalised through
 * {@link #canonicalize(String)} before they reach the query builder.
 */
public enum MovieGenre {

    ACTION("Action"),
    ADVENTURE("Adventure"),
    ANIMATION("Animation"),
    COMEDY("Comedy"),
    CRIME("Crime"),
    DOCUMENTARY("Documentary"),
    DRAMA("Drama"),
    FAMILY("Family"),
    FANTASY("Fantasy"),
    HISTORY("History"),
    HORROR("Horror"),
    MUSIC("Music"),
    MYSTERY("Mystery"),
    ROMANCE("Romance"),
    SCIENCE_FICTION("Science Fiction"),
    TV_MOVIE("TV Movie"),
    THRILLER("Thriller"),
    WAR("War"),
    WESTERN("Western");

    private static final Map<String, String> BY_LOWERCASE_NAME = buildLookup();

    private final String displayName;

    MovieGenre(String displayName) {
        this.displayName = displayName;
    }

    /** The value exactly as it is indexed, e.g. {@code "Science Fiction"}. */
    public String displayName() {
        return displayName;
    }

    /** Every genre in declaration order, for the filter picker. */
    public static List<String> displayNames() {
        return List.copyOf(BY_LOWERCASE_NAME.values());
    }

    /**
     * Maps a user-supplied genre onto the casing used in the index.
     *
     * <p>Values outside this enum are passed through trimmed rather than dropped:
     * the index may legitimately hold genres this enum does not know about, and
     * discarding the term would <em>widen</em> the search instead of narrowing it.
     *
     * @return the canonical genre, or {@code null} when {@code raw} is null or blank
     */
    public static String canonicalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return BY_LOWERCASE_NAME.getOrDefault(trimmed.toLowerCase(Locale.ROOT), trimmed);
    }

    private static Map<String, String> buildLookup() {
        Map<String, String> lookup = new LinkedHashMap<>();
        for (MovieGenre genre : values()) {
            lookup.put(genre.displayName.toLowerCase(Locale.ROOT), genre.displayName);
        }
        return Collections.unmodifiableMap(lookup);
    }
}
