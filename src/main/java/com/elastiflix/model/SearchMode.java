package com.elastiflix.model;

public enum SearchMode {
    BM25, ELSER, E5, HYBRID;

    public static SearchMode fromString(String value) {
        if (value == null) return BM25;
        return switch (value.toUpperCase()) {
            case "ELSER" -> ELSER;
            case "E5"    -> E5;
            case "HYBRID" -> HYBRID;
            default      -> BM25;
        };
    }

    public String label() {
        return switch (this) {
            case BM25   -> "BM25 (Keyword)";
            case ELSER  -> "Semantic (ELSER)";
            case E5     -> "Semantic (E5)";
            case HYBRID -> "Hybrid (BM25 + ELSER)";
        };
    }
}
