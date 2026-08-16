package com.elastiflix.controller.web;

import com.elastiflix.config.AppProperties;
import com.elastiflix.controller.MovieSearchParams;
import com.elastiflix.exception.InferenceEndpointMissingException;
import com.elastiflix.exception.RerankUnavailableException;
import com.elastiflix.exception.SearchUnavailableException;
import com.elastiflix.model.MovieGenre;
import com.elastiflix.model.MovieSearchPage;
import com.elastiflix.model.ReleaseYear;
import com.elastiflix.model.ResultsView;
import com.elastiflix.model.SearchMode;
import com.elastiflix.service.MovieService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Year;
import java.util.List;
import java.util.Objects;

/**
 * Renders the search results page, degrading gracefully (inline warning, no error page)
 * when the requested search mode needs an inference endpoint that is not deployed.
 */
@Controller
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final MovieService movieService;
    private final AppProperties appProperties;

    public SearchController(MovieService movieService, AppProperties appProperties) {
        this.movieService = movieService;
        this.appProperties = appProperties;
    }

    @GetMapping("/search")
    public String search(
            @ModelAttribute MovieSearchParams params,
            @RequestParam(required = false) String view,
            Model model
    ) {
        // The binder leaves `q` at its "" initialiser when the parameter is absent, but
        // guard anyway: this method must not depend on that detail staying true.
        String query = params.getQ() != null ? params.getQ() : "";
        // Same rule the service applies, so the picker never offers a size the server would
        // silently replace — including on the empty-query page, where no search runs to correct it.
        int pageSize = appProperties.effectivePageSize(params.getSize());

        model.addAttribute("pageTitle", query.isBlank() ? "Search" : query);
        model.addAttribute("modes", SearchMode.values());
        // Normalize through SearchMode so the UI always reflects the mode actually searched.
        model.addAttribute("currentMode", SearchMode.fromString(params.getMode()).name());
        model.addAttribute("query", query);
        // Normalize so ?view=anything-else still renders a layout instead of an empty page.
        model.addAttribute("view", ResultsView.fromString(view).value());
        model.addAttribute("sort", params.getSort());
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("allGenres", MovieGenre.displayNames());
        model.addAttribute("selectedGenres", canonicalGenres(params.getGenres()));
        model.addAttribute("selectedYear", params.getYear());
        model.addAttribute("selectedRating", params.getRating());
        // An implausible year is dropped from the query rather than sent to Elasticsearch, where
        // it could only ever match nothing. Say so — otherwise the user sees a full, apparently
        // unfiltered result set and no hint that their filter was discarded.
        if (!ReleaseYear.isPlausible(params.getYear())) {
            model.addAttribute("filterNotice", "Ignored the release year " + params.getYear()
                    + " — only years between " + ReleaseYear.MIN_YEAR + " and "
                    + (Year.now().getValue() + ReleaseYear.MAX_YEARS_AHEAD) + " can match a film.");
        }

        if (!query.isBlank()) {
            try {
                MovieSearchPage results = movieService.search(
                        query, params.getMode(), params.getPage(), params.getSize(), params.toFilters(), params.getSort());
                model.addAttribute("results", results);
                // The service may have clamped the requested size — keep the picker and the
                // pagination links in step with the page that was actually served.
                model.addAttribute("pageSize", results.getPageSize());
            } catch (InferenceEndpointMissingException e) {
                log.info("Falling back to inline warning: {}", e.getMessage());
                model.addAttribute("searchErrorTitle", "Inference endpoint not available");
                model.addAttribute("searchError", e.getMessage() +
                        " Please create it via Kibana → Machine Learning → Trained Models, or switch to "
                        + fallbackSuggestion(params.getMode()) + " search.");
            } catch (RerankUnavailableException e) {
                // The endpoint exists but the provider refused us — a different fix from a
                // missing endpoint, and one the cluster admin cannot make.
                log.warn("Rerank endpoint unavailable: {}", e.getMessage());
                model.addAttribute("searchErrorTitle", "Reranking unavailable");
                model.addAttribute("searchError", e.getMessage() +
                        " Check the endpoint's credentials and quota, or switch to Semantic (ELSER) search.");
            } catch (SearchUnavailableException e) {
                log.warn("Search unavailable for mode={}: {}", params.getMode(), e.getMessage());
                // Not an inference problem at all — usually the cluster itself. Saying
                // "inference endpoint not available" here would send the user after the wrong fix.
                model.addAttribute("searchErrorTitle", "Search unavailable");
                model.addAttribute("searchError", e.getMessage());
            }
        }

        return "search";
    }

    /**
     * The mode to recommend when the requested one needs an endpoint that is not there.
     *
     * <p>A reranked search already has ELSER working underneath it, so dropping to plain
     * ELSER keeps semantic recall; suggesting BM25 there would throw that away.
     */
    private static String fallbackSuggestion(String requestedMode) {
        return SearchMode.fromString(requestedMode).isReranked()
                ? "Semantic (ELSER)"
                : "BM25 (Keyword)";
    }

    /** Matches the submitted genres to the casing used in the index so they stay selected in the picker. */
    private static List<String> canonicalGenres(List<String> submitted) {
        if (submitted == null) {
            return List.of();
        }
        return submitted.stream()
                .map(MovieGenre::canonicalize)
                .filter(Objects::nonNull)
                .toList();
    }
}
