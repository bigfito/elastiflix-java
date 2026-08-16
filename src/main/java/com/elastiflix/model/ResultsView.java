package com.elastiflix.model;

import java.util.Locale;

/**
 * Layout of the search results list.
 *
 * <p>Normalising the {@code view} request parameter through this enum stops an
 * unrecognised value (e.g. {@code ?view=foo}) from rendering a page where
 * neither the grid nor the list branch matches and the results silently vanish.
 */
public enum ResultsView {
    GRID, LIST;

    /** Layout used when a request omits {@code view} or sends an unrecognised one. */
    public static ResultsView defaultView() {
        return GRID;
    }

    /** Parses a view name case-insensitively, falling back to {@link #defaultView()}. */
    public static ResultsView fromString(String value) {
        if (value == null || value.isBlank()) {
            return defaultView();
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return defaultView();
        }
    }

    /** Lowercase form used in URLs and compared against in the templates. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
