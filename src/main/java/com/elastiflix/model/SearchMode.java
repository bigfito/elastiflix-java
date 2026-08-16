package com.elastiflix.model;

import java.util.Locale;

/** The search strategies offered by the UI and REST API. */
public enum SearchMode {
    TITLE, BM25, ELSER, E5, HYBRID, ELSER_JINA;

    /**
     * Mode used when a request omits {@code mode} or sends an unrecognised one.
     *
     * <p>Single source of truth for the default: {@link #fromString}, the landing
     * page's pre-selected radio button and {@code MovieSearchParams} all read it.
     */
    public static SearchMode defaultMode() {
        return TITLE;
    }

    /** Parses a mode name case-insensitively, falling back to {@link #defaultMode()} for unknown/blank values. */
    public static SearchMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return defaultMode();
        }
        try {
            // Locale.ROOT: locale-sensitive casing (e.g. Turkish dotless i) must not affect parsing.
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return defaultMode();
        }
    }

    /** Human-readable label shown in the mode picker. */
    public String label() {
        return switch (this) {
            case TITLE      -> "Title & Original Title (Standard)";
            case BM25       -> "BM25 (Keyword)";
            case ELSER      -> "Semantic (ELSER)";
            case E5         -> "Semantic (E5)";
            case HYBRID     -> "Hybrid (BM25 + ELSER)";
            case ELSER_JINA -> "Hybrid (ELSER + Jina rerank)";
        };
    }

    /**
     * Whether this mode is built on a {@code retriever} rather than a plain {@code query}.
     *
     * <p>Elasticsearch forbids a top-level {@code sort} alongside any retriever, so these
     * modes have to degrade to BM25 when the user asks for one.
     */
    public boolean usesRetriever() {
        return this == HYBRID || this == ELSER_JINA;
    }

    /** Whether this mode sends candidates through a reranking inference endpoint. */
    public boolean isReranked() {
        return this == ELSER_JINA;
    }
}
