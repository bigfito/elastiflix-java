package com.elastiflix.exception;

/**
 * Thrown when the reranking inference endpoint exists but refused the request — a rejected
 * API key, an exhausted quota, or a rate limit.
 *
 * <p>Distinct from {@link InferenceEndpointMissingException} on purpose. A reranked mode is
 * the only one that depends on an inference <em>provider</em> rather than just the cluster,
 * so "search is temporarily unavailable" would send someone debugging Elasticsearch when the
 * actual problem is the reranker's credentials or quota.
 */
public class RerankUnavailableException extends ElastiflixException {

    private final String inferenceId;

    public RerankUnavailableException(String inferenceId, String reason, Throwable cause) {
        super("RERANK_UNAVAILABLE",
                "The reranking endpoint \"" + inferenceId + "\" rejected the request (" + reason + ").",
                cause);
        this.inferenceId = inferenceId;
    }

    public String inferenceId() {
        return inferenceId;
    }
}
