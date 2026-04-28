package com.elastiflix.model;

public enum SearchMode {
    TITLE, BM25, ELSER, E5, HYBRID;

    public static SearchMode fromString(String value) {
        if (value == null) return TITLE;
        return switch (value.toUpperCase()) {
            case "TITLE"  -> TITLE;
            case "BM25"   -> BM25;
            case "ELSER"  -> ELSER;
            case "E5"     -> E5;
            case "HYBRID" -> HYBRID;
            default      -> TITLE;
        };
    }

    public String label() {
        return switch (this) {
            case TITLE  -> "Title & Original Title (Standard)";
            case BM25   -> "BM25 (Keyword)";
            case ELSER  -> "Semantic (ELSER)";
            case E5     -> "Semantic (E5)";
            case HYBRID -> "Hybrid (BM25 + ELSER)";
        };
    }
}
