package com.elastiflix.exception;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shapes here are copied from a real Elasticsearch 8.17.0 container, not invented.
 *
 * <p>A flat error (plain {@code semantic} query) puts the cause in the top-level message. A
 * retriever does not: it answers with a wrapper and attaches the real cause as a suppressed
 * exception. Testing only the flat shape is what let a broken reranked mode ship.
 */
class ElasticsearchErrorsTest {

    /** What a missing ELSER endpoint looks like for ELSER, E5 and HYBRID: the cause is right there. */
    private static ElasticsearchException flatMissingEndpoint() {
        return new ElasticsearchException("es/search", ErrorResponse.of(r -> r
                .status(404)
                .error(e -> e
                        .type("resource_not_found_exception")
                        .reason("Inference endpoint not found [elser]"))));
    }

    /**
     * What a missing rerank endpoint looks like for ELSER_JINA. Note that nothing in the
     * top-level type or reason mentions inference at all.
     */
    private static ElasticsearchException wrappedMissingRerankEndpoint() {
        return new ElasticsearchException("es/search", ErrorResponse.of(r -> r
                .status(500)
                .error(e -> e
                        .type("status_exception")
                        .reason("[text_similarity_reranker] search failed - retrievers '[standard]' returned "
                                + "errors. All failures are attached as suppressed exceptions.")
                        .suppressed(s -> s
                                .type("search_phase_execution_exception")
                                .reason("Computing updated ranks for results failed")
                                .causedBy(c -> c
                                        .type("resource_not_found_exception")
                                        .reason("Inference endpoint not found [eis-jina-reranker]"))))));
    }

    /** The provider answered, but refused us — also wrapped by the reranker. */
    private static ElasticsearchException wrappedRerankRejection(String providerReason) {
        return new ElasticsearchException("es/search", ErrorResponse.of(r -> r
                .status(500)
                .error(e -> e
                        .type("status_exception")
                        .reason("[text_similarity_reranker] search failed - retrievers '[standard]' returned errors.")
                        .suppressed(s -> s
                                .type("search_phase_execution_exception")
                                .reason("Computing updated ranks for results failed")
                                .causedBy(c -> c.type("status_exception").reason(providerReason))))));
    }

    // ---------------------------------------------------------------- missing endpoint

    @Test
    void recognisesAMissingEndpointReportedDirectly() {
        assertThat(ElasticsearchErrors.mentionsMissingInferenceEndpoint(flatMissingEndpoint())).isTrue();
    }

    @Test
    void recognisesAMissingEndpointHiddenUnderTheRetrieverWrapper() {
        // The regression test for the bug: getMessage() names none of the markers.
        ElasticsearchException failure = wrappedMissingRerankEndpoint();

        assertThat(failure.getMessage())
                .as("precondition: the flattened message really does hide the cause")
                .doesNotContain("resource_not_found_exception")
                .doesNotContain("Inference endpoint not found");
        assertThat(ElasticsearchErrors.mentionsMissingInferenceEndpoint(failure)).isTrue();
    }

    @Test
    void recognisesAnUnallocatedTrainedModel() {
        ElasticsearchException failure = new ElasticsearchException("es/search", ErrorResponse.of(r -> r
                .status(429)
                .error(e -> e.type("trained_model_deployment_not_allocated").reason("model is not allocated"))));

        assertThat(ElasticsearchErrors.mentionsMissingInferenceEndpoint(failure)).isTrue();
    }

    @Test
    void doesNotBlameInferenceForAnUnrelatedClusterError() {
        ElasticsearchException failure = new ElasticsearchException("es/search", ErrorResponse.of(r -> r
                .status(503)
                .error(e -> e.type("search_phase_execution_exception").reason("all shards failed"))));

        assertThat(ElasticsearchErrors.mentionsMissingInferenceEndpoint(failure)).isFalse();
    }

    @Test
    void doesNotBlameInferenceForAResourceNotFoundAboutSomethingElse() {
        // resource_not_found_exception on its own is not enough — the reason has to agree.
        ElasticsearchException failure = new ElasticsearchException("es/search", ErrorResponse.of(r -> r
                .status(404)
                .error(e -> e.type("resource_not_found_exception").reason("no such snapshot repository [backups]"))));

        assertThat(ElasticsearchErrors.mentionsMissingInferenceEndpoint(failure)).isFalse();
    }

    // ---------------------------------------------------------------- rerank rejection

    @Test
    void classifiesARefusedRerankKeyThroughTheWrapper() {
        assertThat(ElasticsearchErrors.rerankRejectionReason(
                wrappedRerankRejection("Received an authentication error status code for request ... [401]")))
                .isEqualTo("authentication was refused");
    }

    @Test
    void classifiesARateLimitedRerankerThroughTheWrapper() {
        assertThat(ElasticsearchErrors.rerankRejectionReason(
                wrappedRerankRejection("Received a rate limit status code [429]")))
                .isEqualTo("rate limited or out of quota");
    }

    @Test
    void classifiesAnExhaustedQuota() {
        assertThat(ElasticsearchErrors.rerankRejectionReason(
                wrappedRerankRejection("monthly QUOTA exhausted for this key")))
                .isEqualTo("rate limited or out of quota");
    }

    @Test
    void reportsNoRejectionReasonForAnOrdinaryFailure() {
        assertThat(ElasticsearchErrors.rerankRejectionReason(
                wrappedRerankRejection("shard is unavailable"))).isNull();
    }

    // ---------------------------------------------------------------- tree walking

    @Test
    void flattenVisitsTheCauseItsSuppressedCausesAndTheirChain() {
        ErrorCause root = wrappedMissingRerankEndpoint().error();

        assertThat(ElasticsearchErrors.flatten(root))
                .extracting(ErrorCause::type)
                .containsExactly("status_exception", "search_phase_execution_exception", "resource_not_found_exception");
    }

    @Test
    void flattenToleratesAnAbsentErrorTree() {
        assertThat(ElasticsearchErrors.flatten(null)).isEmpty();
    }

    @Test
    void flattenToleratesACauseWithNoReason() {
        ErrorCause bare = ErrorCause.of(c -> c.type("status_exception"));

        assertThat(ElasticsearchErrors.flatten(bare)).hasSize(1);
        assertThat(ElasticsearchErrors.rerankRejectionReason(
                new ElasticsearchException("es/search", ErrorResponse.of(r -> r.status(500).error(e -> e
                        .type("status_exception")))))).isNull();
    }

    @Test
    void stopsWalkingASelfReferentialTreeInsteadOfRecursingForever() {
        // Defensive: a cause chain deeper than the cap must terminate, not blow the stack.
        ErrorCause deep = ErrorCause.of(c -> c.type("level_20").reason("deepest"));
        for (int level = 19; level >= 0; level--) {
            ErrorCause child = deep;
            int current = level;
            deep = ErrorCause.of(c -> c.type("level_" + current).causedBy(child));
        }

        assertThat(ElasticsearchErrors.flatten(deep)).hasSizeLessThan(20);
    }
}
