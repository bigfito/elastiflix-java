package com.elastiflix.exception;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the <em>structured</em> error an Elasticsearch request failed with, so the service
 * layer can tell a missing inference endpoint apart from a cluster outage.
 *
 * <p><strong>Why this exists.</strong> The obvious way to classify a failure is to match on
 * {@link ElasticsearchException#getMessage()}. That works for a plain query, where the message
 * reads:
 *
 * <pre>{@code [es/search] failed: [resource_not_found_exception] Inference endpoint not found [elser]}</pre>
 *
 * <p>It does <em>not</em> work for a retriever. When the reranker's endpoint is missing,
 * Elasticsearch 8.17 answers with a wrapper and hides the real cause underneath it:
 *
 * <pre>{@code
 * status_exception: [text_similarity_reranker] search failed - retrievers '[standard]'
 *                   returned errors. All failures are attached as suppressed exceptions.
 *   suppressed[0] search_phase_execution_exception: Computing updated ranks for results failed
 *     causedBy    resource_not_found_exception: Inference endpoint not found [eis-jina-reranker]
 * }</pre>
 *
 * <p>The words that identify the failure appear nowhere in {@code getMessage()}. Matching on the
 * message therefore misclassified every reranked failure as a generic outage, which is why this
 * class walks {@code suppressed} and {@code caused_by} instead.
 */
public final class ElasticsearchErrors {

    /**
     * How deep the error tree is walked. Real responses nest two or three levels; the cap is a
     * guard against a self-referential tree rather than a real limit.
     */
    private static final int MAX_DEPTH = 8;

    /** Error type Elasticsearch uses when an inference endpoint or trained model is absent. */
    private static final String RESOURCE_NOT_FOUND = "resource_not_found_exception";

    /** Error type raised when the model exists but has no allocation to serve the request. */
    private static final String MODEL_NOT_ALLOCATED = "trained_model_deployment_not_allocated";

    private ElasticsearchErrors() {
    }

    /**
     * Whether any cause in the tree says an inference endpoint is missing.
     *
     * @param e the failure reported by the Elasticsearch client
     */
    public static boolean mentionsMissingInferenceEndpoint(ElasticsearchException e) {
        return flatten(rootCauseOf(e)).stream().anyMatch(ElasticsearchErrors::isMissingEndpoint);
    }

    /**
     * Classifies a rerank failure that is <em>not</em> a missing endpoint: the endpoint is there,
     * but the inference provider turned the request away.
     *
     * @return a short, user-facing reason, or {@code null} when the error is something else
     */
    public static String rerankRejectionReason(ElasticsearchException e) {
        for (ErrorCause cause : flatten(rootCauseOf(e))) {
            String rejection = classifyRejection(reasonOf(cause));
            if (rejection != null) {
                return rejection;
            }
        }
        return null;
    }

    /**
     * Every {@link ErrorCause} in the tree, depth first: the cause itself, whatever it suppressed,
     * and its {@code caused_by} chain.
     *
     * <p>{@code root_cause} is deliberately not followed — Elasticsearch populates it with a copy
     * of the top-level cause, so walking it would traverse the same nodes twice.
     */
    public static List<ErrorCause> flatten(ErrorCause root) {
        List<ErrorCause> causes = new ArrayList<>();
        collect(root, causes, 0);
        return causes;
    }

    private static void collect(ErrorCause cause, List<ErrorCause> into, int depth) {
        if (cause == null || depth > MAX_DEPTH) {
            return;
        }
        into.add(cause);
        for (ErrorCause suppressed : cause.suppressed()) {
            collect(suppressed, into, depth + 1);
        }
        collect(cause.causedBy(), into, depth + 1);
    }

    private static boolean isMissingEndpoint(ErrorCause cause) {
        String type = cause.type() != null ? cause.type() : "";
        String reason = reasonOf(cause);
        return MODEL_NOT_ALLOCATED.equals(type)
                // A bare resource_not_found_exception could be about something other than
                // inference, so the reason has to agree before we blame an endpoint.
                || (RESOURCE_NOT_FOUND.equals(type) && reason.contains("inference"))
                || reason.contains("inference endpoint not found");
    }

    private static String classifyRejection(String reason) {
        if (reason.contains("429") || reason.contains("rate limit") || reason.contains("too many requests")
                || reason.contains("quota")) {
            return "rate limited or out of quota";
        }
        if (reason.contains("401") || reason.contains("403") || reason.contains("unauthorized")
                || reason.contains("forbidden") || reason.contains("invalid api key")
                || reason.contains("authentication")) {
            return "authentication was refused";
        }
        return null;
    }

    /** The cause tree of a failure, or {@code null} when the client gave us no structured error. */
    private static ErrorCause rootCauseOf(ElasticsearchException e) {
        return e.error() != null ? e.error() : null;
    }

    /** Lowercased reason text, never null, so callers can match on it without a guard. */
    private static String reasonOf(ErrorCause cause) {
        return cause.reason() != null ? cause.reason().toLowerCase(Locale.ROOT) : "";
    }
}
