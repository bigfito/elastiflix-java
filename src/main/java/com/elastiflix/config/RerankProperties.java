package com.elastiflix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Settings for the reranking stage used by {@code SearchMode.ELSER_JINA}, bound from
 * the {@code elasticsearch.rerank.*} prefix.
 *
 * @param inferenceId ID of an inference endpoint with the {@code rerank} task type. Defaults to
 *                    {@code eis-jina-reranker}, an Elastic Inference Service endpoint running
 *                    {@code jina-reranker-v3.5}:
 *                    <pre>{@code
 *                    PUT _inference/rerank/eis-jina-reranker
 *                    { "service": "elastic",
 *                      "service_settings": { "model_id": "jina-reranker-v3.5" } }
 *                    }</pre>
 *                    EIS manages the provider key, so none is needed here. Point it at the
 *                    preconfigured {@code .jina-reranker-v3.5}, at
 *                    {@code .rerank-v1-elasticsearch} for Elastic's own reranker, or at any
 *                    endpoint you registered yourself.
 * @param windowSize  how many candidates ELSER hands to the reranker. This is the mode's
 *                    latency and cost dial, and it also caps how deep results can be paged:
 *                    only these documents are reranked, so only these can be returned.
 *                    <p>{@code jina-reranker-v3.5} is listwise — query and candidates share a
 *                    single 131k-token context — so the ceiling is that token budget rather
 *                    than a fixed document count. <strong>If you point {@code inferenceId} at a
 *                    {@code jina-reranker-v3} endpoint instead, keep this at 64 or below</strong>:
 *                    v3 is documented as reranking at most 64 documents per call.
 */
@Validated
@ConfigurationProperties(prefix = "elasticsearch.rerank")
public record RerankProperties(
        @DefaultValue("eis-jina-reranker") @NotBlank String inferenceId,
        @DefaultValue("50") @Min(1) @Max(1000) int windowSize
) {
}
