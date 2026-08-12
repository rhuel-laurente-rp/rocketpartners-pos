package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QuickAddPanel}'s search, sort, and pagination. Pure view state — no JFrame
 * — so these run headless. Capacity (tiles per page) is forced via {@code setCapacityForTest} so
 * the paging assertions don't depend on a laid-out pixel size.
 */
class QuickAddPanelTest {

    private static QuickAddPanel panelOf(List<Item> items) {
        return new QuickAddPanel(items, item -> { });
    }

    private static List<Item> widgets(int n) {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // Zero-padded so lexical name order is stable and predictable.
            out.add(new Item("U" + i, String.format("Widget %02d", i), new BigDecimal(i + 1)));
        }
        return out;
    }

    private static final List<Item> SORTABLE = List.of(
            new Item("1", "Cola", new BigDecimal("2.00")),
            new Item("2", "Cola Zero", new BigDecimal("2.50")),
            new Item("3", "Apple", new BigDecimal("0.50")),
            new Item("4", "Water", new BigDecimal("1.00")));

    // ---- Pagination bounds ------------------------------------------------

    @Test
    void pageCount_derivesFromCapacityAndFilteredSize() {
        QuickAddPanel p = panelOf(widgets(25));
        p.setCapacityForTest(5, 10);
        assertThat(p.getPageCountForTest()).isEqualTo(3); // ceil(25/10)
        assertThat(p.getPageForTest()).isZero();
    }

    @Test
    void nextAndPrev_clampAtBothEnds() {
        QuickAddPanel p = panelOf(widgets(25));
        p.setCapacityForTest(5, 10); // 3 pages: 0,1,2

        p.prevForTest();
        assertThat(p.getPageForTest()).isZero(); // clamps at first

        p.nextForTest();
        p.nextForTest();
        p.nextForTest(); // past the end
        assertThat(p.getPageForTest()).isEqualTo(2); // clamps at last
    }

    @Test
    void firstAndLast_jumpToEnds() {
        QuickAddPanel p = panelOf(widgets(25));
        p.setCapacityForTest(5, 10);

        p.lastForTest();
        assertThat(p.getPageForTest()).isEqualTo(2);

        p.firstForTest();
        assertThat(p.getPageForTest()).isZero();
    }

    @Test
    void singlePage_whenEverythingFits() {
        QuickAddPanel p = panelOf(widgets(6));
        p.setCapacityForTest(3, 30);
        assertThat(p.getPageCountForTest()).isEqualTo(1);
        p.nextForTest();
        assertThat(p.getPageForTest()).isZero();
    }

    @Test
    void pagerControls_disableRatherThanHide_atBoundaries() {
        QuickAddPanel p = panelOf(widgets(25));
        p.setCapacityForTest(5, 10); // 3 pages: 0,1,2

        // First page: First/Prev are dead ends, Next/Last live — but all four stay visible. Hiding
        // First/Prev would shift the cluster sideways and move Next under a finger already aimed.
        p.firstForTest();
        assertThat(p.firstEnabledForTest()).isFalse();
        assertThat(p.prevEnabledForTest()).isFalse();
        assertThat(p.nextEnabledForTest()).isTrue();
        assertThat(p.lastEnabledForTest()).isTrue();
        assertThat(p.pagerControlsAllVisibleForTest()).isTrue();

        // Last page: the mirror image — Next/Last dead, First/Prev live, all still visible.
        p.lastForTest();
        assertThat(p.nextEnabledForTest()).isFalse();
        assertThat(p.lastEnabledForTest()).isFalse();
        assertThat(p.firstEnabledForTest()).isTrue();
        assertThat(p.prevEnabledForTest()).isTrue();
        assertThat(p.pagerControlsAllVisibleForTest()).isTrue();
    }

    // ---- Tile count matches capacity --------------------------------------

    @Test
    void fullPage_rendersExactlyCapacityTiles() {
        QuickAddPanel p = panelOf(widgets(25));
        p.setCapacityForTest(4, 8);
        assertThat(p.tileCountForTest()).isEqualTo(8);
    }

    @Test
    void lastPage_rendersOnlyTheRemainder() {
        QuickAddPanel p = panelOf(widgets(25));
        p.setCapacityForTest(4, 8); // 4 pages (8,8,8,1)
        p.lastForTest();
        assertThat(p.getPageForTest()).isEqualTo(3);
        assertThat(p.tileCountForTest()).isEqualTo(1); // 25 - 24
    }

    // ---- Search filtering -------------------------------------------------

    @Test
    void search_filtersByLabel_caseInsensitive() {
        QuickAddPanel p = panelOf(SORTABLE);
        p.setCapacityForTest(4, 50);

        p.setQueryForTest("cola");
        assertThat(p.filteredSortedForTest()).hasSize(2);

        p.setQueryForTest("WATER");
        assertThat(p.filteredSortedForTest()).hasSize(1);

        p.setQueryForTest("");
        assertThat(p.filteredSortedForTest()).hasSize(4);
    }

    @Test
    void search_resetsToFirstPage() {
        QuickAddPanel p = panelOf(widgets(25));
        p.setCapacityForTest(5, 10);
        p.lastForTest();
        assertThat(p.getPageForTest()).isEqualTo(2);

        p.setQueryForTest("Widget 0"); // matches Widget 00..09 → 10 items → 1 page
        assertThat(p.getPageForTest()).isZero();
    }

    // ---- Sort ordering ----------------------------------------------------

    @Test
    void sort_nameAscendingAndDescending() {
        QuickAddPanel p = panelOf(SORTABLE);
        p.setCapacityForTest(4, 50);

        p.setSortForTest(QuickAddPanel.SortMode.NAME_ASC);
        assertThat(labels(p)).containsExactly("Apple", "Cola", "Cola Zero", "Water");

        p.setSortForTest(QuickAddPanel.SortMode.NAME_DESC);
        assertThat(labels(p)).containsExactly("Water", "Cola Zero", "Cola", "Apple");
    }

    @Test
    void sort_priceAscendingAndDescending() {
        QuickAddPanel p = panelOf(SORTABLE);
        p.setCapacityForTest(4, 50);

        p.setSortForTest(QuickAddPanel.SortMode.PRICE_ASC);
        assertThat(labels(p)).containsExactly("Apple", "Water", "Cola", "Cola Zero");

        p.setSortForTest(QuickAddPanel.SortMode.PRICE_DESC);
        assertThat(labels(p)).containsExactly("Cola Zero", "Cola", "Water", "Apple");
    }

    private static List<String> labels(QuickAddPanel p) {
        List<String> out = new ArrayList<>();
        for (Item i : p.filteredSortedForTest()) out.add(i.getDisplayLabel());
        return out;
    }
}
