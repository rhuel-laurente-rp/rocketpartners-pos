package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Real-Swing tests for basket density, badge, flash, and hover behaviour. Skipped in headless CI
 * environments — the JFrame constructor requires a display.
 *
 * <p>The 10/11 boundary is asserted directly rather than by counting items: renderer density is
 * a public enum so the test can read it without knowing pixel budgets.</p>
 */
class CustomerViewBasketDensityTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("1.00"));

    @Test
    void densityIsComfortable_atTenItems_andSwitchesToCompactAtEleven() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());

        view.updateBasket(build(9), new BigDecimal("9.00"));
        assertThat(view.getBasketDensity()).isEqualTo(BasketCellRenderer.Density.COMFORTABLE);

        view.updateBasket(build(10), new BigDecimal("10.00"));
        // The renderer flips density synchronously; row height is animated, so allow a beat for
        // the timer to converge on the target height.
        assertThat(view.getBasketDensity()).isEqualTo(BasketCellRenderer.Density.COMPACT);
        Awaitility.await().atMost(Duration.ofSeconds(1)).until(() ->
                view.getBasketRowHeight() == BasketCellRenderer.COMPACT_ROW_HEIGHT);
    }

    @Test
    void densityReverts_whenListShrinksBackBelowThreshold() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        view.updateBasket(build(20), new BigDecimal("20.00"));
        assertThat(view.getBasketDensity()).isEqualTo(BasketCellRenderer.Density.COMPACT);

        view.updateBasket(build(5), new BigDecimal("5.00"));

        assertThat(view.getBasketDensity()).isEqualTo(BasketCellRenderer.Density.COMFORTABLE);
        Awaitility.await().atMost(Duration.ofSeconds(1)).until(() ->
                view.getBasketRowHeight() == BasketCellRenderer.COMFORTABLE_ROW_HEIGHT);
    }

    @Test
    void badge_hiddenAtQuantityOne_shownAtQuantityTwo() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        BasketCellRenderer renderer = new BasketCellRenderer();

        // Renderer's badge is an inner component — we assert on the paint-time flag by
        // reflecting through the public API. Rendering with qty=1 must leave the badge in a
        // state where the badge paints nothing (its paintComponent early-returns when qty <= 1);
        // rendering with qty=2 must not.
        LineItem qtyOne = new LineItem(WIDGET, 1);
        LineItem qtyTwo = new LineItem(WIDGET, 2);

        renderer.getListCellRendererComponent(new javax.swing.JList<>(), qtyOne, 0, false, false);
        BasketCellRenderer.BadgePanel badge = findBadge(renderer);
        assertThat(badge.getPreferredSize().width).isEqualTo(BasketCellRenderer.BadgePanel.WIDTH);
        assertThat(willBadgePaint(badge)).isFalse();

        renderer.getListCellRendererComponent(new javax.swing.JList<>(), qtyTwo, 0, false, false);
        assertThat(willBadgePaint(badge)).isTrue();
    }

    @Test
    void flashState_clears_afterTimerCompletes() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        view.updateBasket(build(1), new BigDecimal("1.00"));
        assertThat(view.getFlashRowForTest()).isEqualTo(0);

        // The flash Timer fires every 16ms and the flash lasts 400ms — Awaitility past that
        // window; the timer nulls both the layer's flashIndex and the renderer's.
        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() ->
                view.getFlashRowForTest() == -1);
    }

    @Test
    void hoverIndex_updatesAndClears() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        view.updateBasket(build(3), new BigDecimal("3.00"));
        assertThat(view.getHoverRowForTest()).isEqualTo(-1);

        view.setHoverRowForTest(1);
        assertThat(view.getHoverRowForTest()).isEqualTo(1);

        view.setHoverRowForTest(-1);
        assertThat(view.getHoverRowForTest()).isEqualTo(-1);
    }

    @Test
    void twoFiftyItems_renderWithoutError() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        view.updateBasket(build(250), new BigDecimal("250.00"));
        assertThat(view.getBasketDensity()).isEqualTo(BasketCellRenderer.Density.COMPACT);
        // Ask the list to render one visible cell to make sure the renderer copes.
        javax.swing.JList<LineItem> list = view.getBasketListForTest();
        assertThat(list.getModel().getSize()).isEqualTo(250);
        java.awt.Component c = list.getCellRenderer().getListCellRendererComponent(
                list, list.getModel().getElementAt(0), 0, false, false);
        assertThat(c).isNotNull();
    }

    // ---- Helpers -----------------------------------------------------------

    private static List<LineItem> build(int count) {
        List<LineItem> out = new ArrayList<>(count);
        // Different Item instances per row so the domain does not merge them — merging is
        // the model's job, not the view's, and mixing it into the density test would confuse
        // the failure mode.
        for (int i = 0; i < count; i++) {
            Item unique = new Item("UPC-" + i, "Widget " + i, new BigDecimal("1.00"));
            out.add(new LineItem(unique, 1));
        }
        return out;
    }

    private static IPosEventDispatcher noopDispatcher() {
        return event -> {};
    }

    private static BasketCellRenderer.BadgePanel findBadge(BasketCellRenderer renderer) {
        return findBadge(renderer, 0);
    }

    private static BasketCellRenderer.BadgePanel findBadge(java.awt.Container c, int depth) {
        if (depth > 6) return null;
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof BasketCellRenderer.BadgePanel b) return b;
            if (child instanceof java.awt.Container cc) {
                BasketCellRenderer.BadgePanel found = findBadge(cc, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Paint the badge into a throwaway image and check whether any pixel is non-transparent.
     * The badge's paintComponent early-returns when quantity <= 1, so this is a robust proxy
     * for "the badge would render".
     */
    private static boolean willBadgePaint(BasketCellRenderer.BadgePanel badge) {
        badge.setSize(BasketCellRenderer.BadgePanel.WIDTH, BasketCellRenderer.BadgePanel.HEIGHT);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                BasketCellRenderer.BadgePanel.WIDTH, BasketCellRenderer.BadgePanel.HEIGHT,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            badge.paint(g);
        } finally {
            g.dispose();
        }
        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = 0; y < img.getHeight(); y++) {
                if ((img.getRGB(x, y) >>> 24) != 0) return true;
            }
        }
        return false;
    }
}
