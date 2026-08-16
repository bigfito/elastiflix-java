package com.elastiflix.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MovieSearchPageTest {

    /** A page with the ordinary cluster result window as its ceiling. */
    private static MovieSearchPage page(long totalHits, int currentPage, int pageSize) {
        return MovieSearchPage.of(List.of(), totalHits, currentPage, pageSize, "batman", "TITLE", "TITLE",
                MovieSearchPage.MAX_RESULT_WINDOW);
    }

    @Test
    void computesTotalPagesFromHitsAndSize() {
        assertThat(page(101, 1, 50).getTotalPages()).isEqualTo(3);
    }

    @Test
    void doesNotDivideByZeroWhenPageSizeIsZero() {
        MovieSearchPage response = page(10, 1, 0);

        assertThat(response.getTotalPages()).isZero();
        assertThat(response.isWindowLimited()).isFalse();
    }

    @Test
    void fromIndexIsZeroWhenThereAreNoResults() {
        MovieSearchPage response = page(0, 1, 50);

        assertThat(response.getFromIndex()).isZero();
        assertThat(response.getToIndex()).isZero();
    }

    @Test
    void computesFromAndToIndexForAMiddlePage() {
        MovieSearchPage response = page(120, 2, 50);

        assertThat(response.getFromIndex()).isEqualTo(51);
        assertThat(response.getToIndex()).isEqualTo(100);
    }

    @Test
    void toIndexIsClampedToTotalHitsOnTheLastPage() {
        assertThat(page(101, 3, 50).getToIndex()).isEqualTo(101);
    }

    @Test
    void hasPreviousAndNextPageReflectCurrentPosition() {
        MovieSearchPage firstPage = page(101, 1, 50);
        MovieSearchPage middlePage = page(101, 2, 50);
        MovieSearchPage lastPage = page(101, 3, 50);

        assertThat(firstPage.hasPreviousPage()).isFalse();
        assertThat(firstPage.hasNextPage()).isTrue();

        assertThat(middlePage.hasPreviousPage()).isTrue();
        assertThat(middlePage.hasNextPage()).isTrue();

        assertThat(lastPage.hasPreviousPage()).isTrue();
        assertThat(lastPage.hasNextPage()).isFalse();
    }

    @Test
    void neverAdvertisesMorePagesThanTheResultWindowCanServe() {
        // 50 000 matches at 50 per page would be 1 000 pages, but from + size must
        // stay within 10 000, so only 200 pages are actually reachable.
        MovieSearchPage response = page(50_000, 1, 50);

        assertThat(response.getTotalPages()).isEqualTo(200);
        assertThat(response.isWindowLimited()).isTrue();
    }

    @Test
    void lastReachablePageReportsNoNextPage() {
        MovieSearchPage response = page(50_000, 200, 50);

        assertThat(response.hasNextPage()).isFalse();
        assertThat(response.hasPreviousPage()).isTrue();
    }

    @Test
    void isNotWindowLimitedWhenEveryMatchIsReachable() {
        assertThat(page(10_000, 1, 50).isWindowLimited()).isFalse();
    }

    @Test
    void honoursATighterCeilingThanTheResultWindow() {
        // A reranked mode only scores rank_window_size candidates, so nothing past 50 is
        // retrievable even though 5 000 documents matched.
        MovieSearchPage response = MovieSearchPage.of(
                List.of(), 5_000, 1, 25, "batman", "ELSER_JINA", "ELSER_JINA", 50);

        assertThat(response.getTotalPages()).isEqualTo(2);
        assertThat(response.isWindowLimited()).isTrue();
        assertThat(response.getReachableDocuments()).isEqualTo(50);
    }

    @Test
    void stillOffersOnePageWhenTheCeilingIsSmallerThanAPage() {
        MovieSearchPage response = MovieSearchPage.of(
                List.of(), 5_000, 1, 100, "batman", "ELSER_JINA", "ELSER_JINA", 50);

        assertThat(response.getTotalPages()).isEqualTo(1);
        assertThat(response.getReachableDocuments()).isEqualTo(50);
    }

    @Test
    void neverClaimsMoreReachableDocumentsThanActuallyMatched() {
        MovieSearchPage response = MovieSearchPage.of(
                List.of(), 3, 1, 25, "batman", "ELSER_JINA", "ELSER_JINA", 50);

        assertThat(response.getReachableDocuments()).isEqualTo(3);
        assertThat(response.isWindowLimited()).isFalse();
    }

    @Test
    void defaultsToTheClusterResultWindowWhenNoCeilingIsGiven() {
        assertThat(page(50_000, 1, 50).getReachableDocuments())
                .isEqualTo(MovieSearchPage.MAX_RESULT_WINDOW);
    }

    @Test
    void keepsRequestedAndEffectiveModeApart() {
        MovieSearchPage response = MovieSearchPage.of(List.of(), 1, 1, 50, "batman", "HYBRID", "BM25",
                MovieSearchPage.MAX_RESULT_WINDOW);

        assertThat(response.getMode()).isEqualTo("HYBRID");
        assertThat(response.getEffectiveMode()).isEqualTo("BM25");
    }

    @Test
    void centresTheNumberedPageLinksOnTheCurrentPage() {
        MovieSearchPage response = page(50_000, 10, 50);

        assertThat(response.getPageWindowStart()).isEqualTo(7);
        assertThat(response.getPageWindowEnd()).isEqualTo(13);
    }

    @Test
    void clampsThePageLinkWindowToTheFirstAndLastPage() {
        MovieSearchPage firstPage = page(300, 1, 50);
        MovieSearchPage lastPage = page(300, 6, 50);

        assertThat(firstPage.getPageWindowStart()).isEqualTo(1);
        assertThat(firstPage.getPageWindowEnd()).isEqualTo(4);
        assertThat(lastPage.getPageWindowStart()).isEqualTo(3);
        assertThat(lastPage.getPageWindowEnd()).isEqualTo(6);
    }
}
