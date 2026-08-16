package com.elastiflix.exception;

/**
 * Thrown when a search mode requires a machine-learning inference endpoint
 * (ELSER, E5, or the ELSER leg of Hybrid) that has not been deployed in the
 * target Elasticsearch cluster.
 *
 * <p>Carries the human-readable {@link #endpointName()} so callers can show a
 * precise, actionable message ("deploy the '...' endpoint via Kibana...").
 */
public class InferenceEndpointMissingException extends ElastiflixException {

    private final String endpointName;

    public InferenceEndpointMissingException(String endpointName, Throwable cause) {
        super("INFERENCE_ENDPOINT_MISSING",
                "The inference endpoint \"" + endpointName + "\" is not deployed in this Elasticsearch cluster.",
                cause);
        this.endpointName = endpointName;
    }

    public String endpointName() {
        return endpointName;
    }
}
