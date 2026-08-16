package com.elastiflix.exception;

/**
 * Thrown at application startup when the {@code ElasticsearchClient} bean
 * cannot be constructed, e.g. because the JVM's TLS provider does not support
 * the requested algorithm.
 */
public class ElasticsearchClientInitializationException extends ElastiflixException {

    public ElasticsearchClientInitializationException(String message, Throwable cause) {
        super("ELASTICSEARCH_CLIENT_INIT_FAILED", message, cause);
    }
}
