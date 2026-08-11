package com.rocketpartners.onboarding.possystem.display;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ProportionalLayout}. Pure layout arithmetic — no Swing peer is required,
 * so these run in headless CI without the {@code assumeFalse(headless)} guard the real-Swing view
 * tests use.
 */
class ProportionalLayoutTest {

    private static JPanel horizontal(int w, int h, float... weights) {
        return laidOut(ProportionalLayout.HORIZONTAL, w, h, weights);
    }

    private static JPanel vertical(int w, int h, float... weights) {
        return laidOut(ProportionalLayout.VERTICAL, w, h, weights);
    }

    private static JPanel laidOut(int axis, int w, int h, float... weights) {
        JPanel parent = new JPanel(new ProportionalLayout(axis));
        for (float weight : weights) {
            parent.add(new JPanel(), weight);
        }
        parent.setSize(w, h);
        parent.doLayout();
        return parent;
    }

    // ---- exact fractions at several sizes ---------------------------------

    @Test
    void horizontal_splitsWidthByExactFraction() {
        JPanel p = horizontal(1000, 400, 0.30f, 0.70f);
        assertThat(p.getComponent(0).getWidth()).isEqualTo(300);
        assertThat(p.getComponent(1).getWidth()).isEqualTo(700);
        // Cross-axis: full inner height, positioned at the origin.
        assertThat(p.getComponent(0).getHeight()).isEqualTo(400);
        assertThat(p.getComponent(1).getHeight()).isEqualTo(400);
        assertThat(p.getComponent(0).getX()).isEqualTo(0);
        assertThat(p.getComponent(1).getX()).isEqualTo(300);
    }

    @Test
    void horizontal_thirtySeventyAtContentWidth() {
        // 1488 is the POS content width; 30/70 lands on the sketch's ~446 / ~1042.
        JPanel p = horizontal(1488, 900, 0.30f, 0.70f);
        assertThat(p.getComponent(0).getWidth()).isEqualTo(446);
        assertThat(p.getComponent(1).getWidth()).isEqualTo(1042);
        assertThat(sumWidth(p)).isEqualTo(1488);
    }

    @Test
    void vertical_splitsHeightByExactFraction_eightyTwenty() {
        JPanel p = vertical(400, 900, 0.80f, 0.20f);
        assertThat(p.getComponent(0).getHeight()).isEqualTo(720);
        assertThat(p.getComponent(1).getHeight()).isEqualTo(180);
        assertThat(p.getComponent(0).getWidth()).isEqualTo(400);
        assertThat(p.getComponent(0).getY()).isEqualTo(0);
        assertThat(p.getComponent(1).getY()).isEqualTo(720);
        assertThat(sumHeight(p)).isEqualTo(900);
    }

    @Test
    void horizontal_seventyThirty_andEvenSplitWithinPayment() {
        // Actions 70 / Payment 30.
        JPanel outer = horizontal(1000, 300, 0.70f, 0.30f);
        assertThat(outer.getComponent(0).getWidth()).isEqualTo(700);
        assertThat(outer.getComponent(1).getWidth()).isEqualTo(300);
        // Card row: even split of the payment sub-column.
        JPanel cardRow = horizontal(300, 50, 0.50f, 0.50f);
        assertThat(cardRow.getComponent(0).getWidth()).isEqualTo(150);
        assertThat(cardRow.getComponent(1).getWidth()).isEqualTo(150);
    }

    // ---- deterministic leftover distribution, sizes sum to container ------

    @Test
    void thirds_sumExactlyToContainer_leftoverIsDeterministic() {
        JPanel p = horizontal(1000, 100, 1f / 3f, 1f / 3f, 1f / 3f);
        int a = p.getComponent(0).getWidth();
        int b = p.getComponent(1).getWidth();
        int c = p.getComponent(2).getWidth();
        assertThat(a + b + c).isEqualTo(1000);
        // Cumulative-edge rounding gives 333 / 334 / 333 — stable, not 333/333/334.
        assertThat(a).isEqualTo(333);
        assertThat(b).isEqualTo(334);
        assertThat(c).isEqualTo(333);
    }

    @Test
    void sumAlwaysEqualsContainer_acrossManyOddSizes() {
        for (int w = 1; w <= 2000; w++) {
            JPanel p = horizontal(w, 10, 0.30f, 0.70f);
            assertThat(sumWidth(p))
                    .as("sum of child widths at container width %d", w)
                    .isEqualTo(w);
        }
    }

    @Test
    void weightsNeedNotSumToOne() {
        // 1 : 3 is the same split as 0.25 : 0.75.
        JPanel p = horizontal(1000, 100, 1f, 3f);
        assertThat(p.getComponent(0).getWidth()).isEqualTo(250);
        assertThat(p.getComponent(1).getWidth()).isEqualTo(750);
    }

    // ---- preferred size is ignored ----------------------------------------

    @Test
    void childPreferredSizeDoesNotPerturbTheSplit() {
        JPanel parent = new JPanel(new ProportionalLayout(ProportionalLayout.HORIZONTAL));
        JPanel greedy = new JPanel();
        greedy.setPreferredSize(new Dimension(5000, 5000));
        JPanel modest = new JPanel();
        modest.setPreferredSize(new Dimension(10, 10));
        parent.add(greedy, 0.30f);
        parent.add(modest, 0.70f);
        parent.setSize(1000, 400);
        parent.doLayout();
        // Despite the 5000px preferred width, greedy still gets exactly 30%.
        assertThat(greedy.getWidth()).isEqualTo(300);
        assertThat(modest.getWidth()).isEqualTo(700);
    }

    // ---- constructor / constraint validation ------------------------------

    @Test
    void rejectsBadAxis() {
        assertThatThrownBy(() -> new ProportionalLayout(7))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonNumericConstraint() {
        JPanel parent = new JPanel(new ProportionalLayout(ProportionalLayout.HORIZONTAL));
        assertThatThrownBy(() -> parent.add(new JPanel(), "half"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeWeight() {
        JPanel parent = new JPanel(new ProportionalLayout(ProportionalLayout.HORIZONTAL));
        assertThatThrownBy(() -> parent.add(new JPanel(), -0.5f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int sumWidth(JPanel p) {
        int sum = 0;
        for (Component c : p.getComponents()) sum += c.getWidth();
        return sum;
    }

    private static int sumHeight(JPanel p) {
        int sum = 0;
        for (Component c : p.getComponents()) sum += c.getHeight();
        return sum;
    }
}
