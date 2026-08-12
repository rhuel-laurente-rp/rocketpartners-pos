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
 * Real-Swing tests for basket density, the quantity column, flash, and hover behaviour. Skipped
 * in headless CI environments — the JFrame constructor requires a display.
 *
 * <p>The 10/11 boundary is asserted directly rather than by counting items: renderer density is
 * a public enum so the test can read it without knowing pixel budgets.</p>
 */
class CustomerViewBasketDensityTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("1.00"));

    @Test
    void densityIsComfortable_atThreshold_andSwitchesToCompactAboveIt() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());

        // Drive the boundary off the renderer's own threshold constant rather than a hard-coded
        // count, so a future change to DENSITY_THRESHOLD can't leave this test asserting a stale
        // 10/11 boundary (which is exactly how it drifted before).
        int threshold = BasketCellRenderer.DENSITY_THRESHOLD;
        view.updateBasket(build(threshold), new BigDecimal(threshold + ".00"));
        assertThat(view.getBasketDensity()).isEqualTo(BasketCellRenderer.Density.COMFORTABLE);

        view.updateBasket(build(threshold + 1), new BigDecimal((threshold + 1) + ".00"));
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
    void qtyColumn_showsNumber_includingOneForSingleQuantityLines() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        BasketCellRenderer renderer = new BasketCellRenderer();

        // The quantity column is plain text now — the number is always shown, including 1, since
        // an empty cell in a table column reads as missing data (a badge could sensibly stay blank
        // at one, a column cannot).
        renderer.getListCellRendererComponent(
                new javax.swing.JList<>(), new LineItem(WIDGET, 1), 0, false, false);
        assertThat(renderer.getQtyTextForTest()).isEqualTo("1");

        renderer.getListCellRendererComponent(
                new javax.swing.JList<>(), new LineItem(WIDGET, 3), 0, false, false);
        assertThat(renderer.getQtyTextForTest()).isEqualTo("3");
    }

    @Test
    void densitySwitch_changesPadding_notFont() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        BasketCellRenderer renderer = new BasketCellRenderer();

        renderer.setDensity(BasketCellRenderer.Density.COMFORTABLE);
        int comfyPad = renderer.getVerticalPaddingForTest();
        java.awt.Font comfyFont = renderer.getValueFontForTest();

        renderer.setDensity(BasketCellRenderer.Density.COMPACT);
        int compactPad = renderer.getVerticalPaddingForTest();
        java.awt.Font compactFont = renderer.getValueFontForTest();

        // Compact tightens the padding relative to comfortable — that is the density knob.
        assertThat(comfyPad).isEqualTo(BasketCellRenderer.COMFORTABLE_ROW_PAD);
        assertThat(compactPad).isEqualTo(BasketCellRenderer.COMPACT_ROW_PAD);
        assertThat(compactPad).isLessThan(comfyPad);

        // The font is untouched by the switch — same instance, still BODY size. Legibility is paid
        // on every sale; density comes from padding, not shrinking the type.
        assertThat(compactFont).isSameAs(comfyFont);
        assertThat(compactFont.getSize2D()).isEqualTo(PosTheme.BODY);
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
}
