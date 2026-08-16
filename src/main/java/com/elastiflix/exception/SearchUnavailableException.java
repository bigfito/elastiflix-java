package com.elastiflix.exception;

/**
 * Thrown when Elasticsearch cannot be reached or returns an error that is not
 * attributable to a specific, user-actionable cause (see {@link InferenceEndpointMissingException}
 * for the one case that does get a dedicated type).
 */
public class SearchUnavailableException extends ElastiflixException {

    public SearchUnavailableException(String message, Throwable cause) {
        super("SEARCH_UNAVAILABLE", message, cause);
    }
}
