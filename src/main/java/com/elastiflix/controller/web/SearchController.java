package com.elastiflix.controller.web;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.elastiflix.model.SearchMode;
import com.elastiflix.model.SearchResponse;
import com.elastiflix.service.MovieService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;

@Controller
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final MovieService movieService;

    public SearchController(MovieService movieService) {
        this.movieService = movieService;
    }

    @GetMapping("/search")
    public String search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "BM25") String mode,
            @RequestParam(defaultValue = "1") int page,
            Model model
    ) throws IOException {
        model.addAttribute("modes", SearchMode.values());
        model.addAttribute("currentMode", mode.toUpperCase());
        model.addAttribute("query", q);

        if (!q.isBlank()) {
            try {
                SearchResponse results = movieService.search(q, mode, page);
                model.addAttribute("results", results);
            } catch (ElasticsearchException e) {
                log.warn("Elasticsearch error during search [mode={}]: status={} message={}",
                        mode, e.status(), e.getMessage());
                if (isInferenceEndpointMissing(e)) {
                    String endpointName = inferenceEndpointName(mode);
                    model.addAttribute("searchError",
                            "The inference endpoint \"" + endpointName + "\" is not deployed in your Elasticsearch cluster. " +
                            "Please create it via Kibana → Machine Learning → Trained Models, or switch to BM25 (Keyword) search.");
                } else {
                    model.addAttribute("searchError",
                            "Elasticsearch error (" + e.status() + "): " + e.getMessage());
                }
            }
        }

        return "search";
    }

    private boolean isInferenceEndpointMissing(ElasticsearchException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        // Only match errors explicitly about inference/trained-model endpoints being absent
        return (msg.contains("resource_not_found_exception") && msg.contains("inference"))
                || msg.contains("Inference endpoint not found")
                || msg.contains("trained_model_deployment_not_allocated");
    }

    private String inferenceEndpointName(String mode) {
        return switch (mode.toUpperCase()) {
            case "ELSER"  -> "elser";
            case "E5"     -> "e5";
            case "HYBRID" -> "elser (required for hybrid RRF)";
            default       -> mode.toLowerCase();
        };
    }
}
