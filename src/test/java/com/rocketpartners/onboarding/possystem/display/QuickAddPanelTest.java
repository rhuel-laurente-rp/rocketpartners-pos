package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.commons.model.Item;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @Test
    void sort_discountsFirst_groupsPromoMarkedAheadOfUnmarked_thenNameAscWithinEachGroup() {
        // Cola + Water carry a deal; Apple + Cola Zero do not. The two marked items lead (name A–Z
        // within the group), the two unmarked follow (name A–Z within the group).
        QuickAddPanel p = panelOf(SORTABLE);
        p.setCapacityForTest(4, 50);
        p.setPromoMarks(Map.of(
                "1", DiscountType.PROMO,          // Cola
                "4", DiscountType.PERCENT_OFF));  // Water
        p.setSortForTest(QuickAddPanel.SortMode.DISCOUNT_FIRST);

        assertThat(labels(p)).containsExactly("Cola", "Water", "Apple", "Cola Zero");
    }

    @Test
    void sort_discountsFirst_withNoMarks_fallsBackToPlainNameOrder() {
        // Engine unreachable / fetch not landed: no item is marked, so the ordering must collapse to
        // a plain name A–Z sort rather than reorder arbitrarily.
        QuickAddPanel p = panelOf(SORTABLE);
        p.setCapacityForTest(4, 50);
        p.setSortForTest(QuickAddPanel.SortMode.DISCOUNT_FIRST);

        assertThat(labels(p)).containsExactly("Apple", "Cola", "Cola Zero", "Water");
    }

    private static List<String> labels(QuickAddPanel p) {
        List<String> out = new ArrayList<>();
        for (Item i : p.filteredSortedForTest()) out.add(i.getDisplayLabel());
        return out;
    }

    // ---- Promotional tile marking + colour legend -------------------------

    private static final Item PROMO_ITEM = new Item("PROMO-UPC", "Monster", new BigDecimal("2.00"));
    private static final Item PCT_ITEM = new Item("PCT-UPC", "Reign", new BigDecimal("3.00"));
    private static final Item PLAIN_ITEM = new Item("PLAIN-UPC", "Water", new BigDecimal("1.00"));

    @Test
    void tileWhoseUpcHasAPromotion_isMarkedWithItsType_oneWithoutIsNot() {
        QuickAddPanel p = panelOf(List.of(PROMO_ITEM, PLAIN_ITEM));
        p.setCapacityForTest(2, 4); // both on one page
        p.setPromoMarks(Map.of("PROMO-UPC", DiscountType.PROMO));

        List<Item> shown = p.currentPageItemsForTest();
        for (int i = 0; i < shown.size(); i++) {
            if ("PROMO-UPC".equals(shown.get(i).getUpc())) {
                assertThat(p.isTilePromoForTest(i)).isTrue();
                assertThat(p.tileMarkTypeForTest(i)).isEqualTo(DiscountType.PROMO);
            } else {
                assertThat(p.isTilePromoForTest(i)).isFalse();
                assertThat(p.tileMarkTypeForTest(i)).isNull();
            }
        }
    }

    @Test
    void legend_showsOneEntryPerDistinctMarkedType() {
        QuickAddPanel p = panelOf(List.of(PROMO_ITEM, PCT_ITEM, PLAIN_ITEM));
        p.setCapacityForTest(3, 6);
        // Two distinct types across the marks -> two legend entries; the plain item is unmarked.
        p.setPromoMarks(Map.of(
                "PROMO-UPC", DiscountType.PROMO,
                "PCT-UPC", DiscountType.PERCENT_OFF));

        assertThat(p.legendVisibleForTest()).isTrue();
        assertThat(p.legendEntryCountForTest()).isEqualTo(2);
    }

    @Test
    void withNoPromoMarks_noTilesMarked_andLegendHidden() {
        // The default state before (or without) any promotional-rules fetch: nothing marked.
        QuickAddPanel p = panelOf(List.of(PROMO_ITEM, PLAIN_ITEM));
        p.setCapacityForTest(2, 4);
        for (int i = 0; i < p.tileCountForTest(); i++) {
            assertThat(p.isTilePromoForTest(i)).isFalse();
        }
        assertThat(p.legendVisibleForTest()).isFalse();
    }

    @Test
    void emptyPromoMarks_marksNothing_andHidesLegend() {
        // An unreachable engine yields an empty map — no tiles marked, no legend, grid still built.
        QuickAddPanel p = panelOf(List.of(PROMO_ITEM, PLAIN_ITEM));
        p.setCapacityForTest(2, 4);
        p.setPromoMarks(Map.of());
        for (int i = 0; i < p.tileCountForTest(); i++) {
            assertThat(p.isTilePromoForTest(i)).isFalse();
        }
        assertThat(p.legendVisibleForTest()).isFalse();
    }
}
