package com.elastiflix.exception;

/**
 * Base type for all domain-specific errors raised by Elastiflix.
 *
 * <p>Unlike the low-level exceptions thrown by the Elasticsearch client
 * ({@code IOException}, {@code ElasticsearchException}), subclasses of this
 * exception carry a stable {@link #errorCode()} and a message that is safe to
 * show directly to an end user.
 */
public abstract class ElastiflixException extends RuntimeException {

    private final String errorCode;

    protected ElastiflixException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Stable, machine-readable identifier for this failure (e.g. {@code SEARCH_UNAVAILABLE}).
     * Safe to expose in API responses and logs.
     */
    public String errorCode() {
        return errorCode;
    }
}
