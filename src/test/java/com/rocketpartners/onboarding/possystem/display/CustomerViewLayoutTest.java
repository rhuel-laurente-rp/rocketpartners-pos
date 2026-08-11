package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Asserts the proportional shell divides exactly as the design calls for: content width 30/70,
 * each column 80/20, the bottom-right row 70/30, and the card-tender row split evenly.
 *
 * <p>These construct a real {@link CustomerView} (a {@link javax.swing.JFrame}), so they are
 * guarded with {@code assumeFalse(headless)} like the other real-Swing view tests. The split
 * arithmetic itself is exercised headlessly by {@link ProportionalLayoutTest}; this test verifies
 * the shell is <em>wired</em> to those fractions. Layout is forced by sizing the content pane and
 * recursing {@code doLayout()} top-down — {@link ProportionalLayout} needs no native peer.</p>
 */
class CustomerViewLayoutTest {

    /** Matches CustomerView.OUTER_PAD. */
    private static final int OUTER_PAD = 12;
    private static final int CONTENT_W = 1512;
    private static final int CONTENT_H = 982;

    @Test
    void contentWidthSplitsThirtySeventy() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = laidOutView();
        try {
            int inner = view.getColumnsRowForTest().getWidth() - 2 * OUTER_PAD;
            int left = view.getLeftColumnForTest().getWidth();
            int right = view.getRightColumnForTest().getWidth();
            assertThat(left).isEqualTo(Math.round(0.30f * inner));
            assertThat(left + right).isEqualTo(inner);
        } finally {
            view.dispose();
        }
    }

    @Test
    void leftColumnSplitsEightyTwenty() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = laidOutView();
        try {
            assertEightyTwentyVertical(view.getLeftColumnForTest());
        } finally {
            view.dispose();
        }
    }

    @Test
    void rightColumnSplitsEightyTwenty() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = laidOutView();
        try {
            assertEightyTwentyVertical(view.getRightColumnForTest());
        } finally {
            view.dispose();
        }
    }

    @Test
    void bottomRowSplitsSeventyThirty() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = laidOutView();
        try {
            Container bottom = view.getBottomRowForTest();
            int inner = bottom.getWidth(); // no border on the bottom row
            int actions = bottom.getComponent(0).getWidth();
            int payment = bottom.getComponent(1).getWidth();
            assertThat(actions).isEqualTo(Math.round(0.70f * inner));
            assertThat(actions + payment).isEqualTo(inner);
        } finally {
            view.dispose();
        }
    }

    @Test
    void cardTenderRowSplitsEvenly() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = laidOutView();
        try {
            Container row = view.getCardTenderRowForTest();
            int debit = row.getComponent(0).getWidth();
            int credit = row.getComponent(1).getWidth();
            assertThat(debit).isEqualTo(credit);
        } finally {
            view.dispose();
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static void assertEightyTwentyVertical(Container column) {
        int inner = column.getHeight(); // columns carry no top/bottom inset
        int top = column.getComponent(0).getHeight();
        int bottom = column.getComponent(1).getHeight();
        assertThat(top).isEqualTo(Math.round(0.80f * inner));
        assertThat(top + bottom).isEqualTo(inner);
    }

    private static CustomerView laidOutView() {
        CustomerView view = new CustomerView("test", List.of(), noop());
        Container content = view.getContentPane();
        content.setSize(CONTENT_W, CONTENT_H);
        layoutTree(content);
        return view;
    }

    private static void layoutTree(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container cc) layoutTree(cc);
        }
    }

    private static IPosEventDispatcher noop() {
        return event -> {};
    }
}
