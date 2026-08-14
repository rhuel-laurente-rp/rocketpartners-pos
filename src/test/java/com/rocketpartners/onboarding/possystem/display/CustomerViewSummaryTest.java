package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Anti-regression cover for the vertical summary tape:
 *
 * <ul>
 *   <li>Rows render Subtotal → Discount → Tax → Total, in the same order as
 *       {@link com.rocketpartners.onboarding.possystem.service.ReceiptFormatter}.</li>
 *   <li>The Discount row is present at zero and non-zero — its preferred height is unchanged
 *       either way (the anti-layout-shift guarantee).</li>
 *   <li>Zero discount reads as MUTED with no minus sign; non-zero as {@code PosTheme.GO} with
 *       a leading minus.</li>
 *   <li>The Total row's value is rendered larger than the component rows.</li>
 *   <li>{@code amountDueValue} tracks {@code totalValue} after each {@code updateBasket}.</li>
 * </ul>
 */
class CustomerViewSummaryTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("1.00"));

    @Test
    void rowOrderMatchesReceiptFormatter() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            List<String> labels = summaryLabelsInOrder(view);
            assertThat(labels).containsExactly("Subtotal", "Discount", "Tax", "TOTAL");
        } finally {
            view.dispose();
        }
    }

    @Test
    void rendersEachSuppliedValue() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            view.updateBasket(List.of(new LineItem(WIDGET, 1)),
                    new BigDecimal("17.70"),
                    new BigDecimal("1.20"),
                    new BigDecimal("1.36"),
                    new BigDecimal("17.86"));
            assertThat(view.getSubtotalValueForTest().getText()).isEqualTo("$17.70");
            assertThat(view.getDiscountValueForTest().getText()).isEqualTo("-$1.20");
            assertThat(view.getTaxValueForTest().getText()).isEqualTo("$1.36");
            assertThat(view.getTotalValueForTest().getText()).isEqualTo("$17.86");
        } finally {
            view.dispose();
        }
    }

    @Test
    void discountRowMutedAtZero_greenWithMinusAtNonZero() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            view.updateBasket(List.of(new LineItem(WIDGET, 1)),
                    new BigDecimal("10.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    new BigDecimal("10.00"));
            assertThat(view.getDiscountValueForTest().getForeground()).isEqualTo(PosTheme.MUTED);
            assertThat(view.getDiscountLabelForTest().getForeground()).isEqualTo(PosTheme.MUTED);
            assertThat(view.getDiscountValueForTest().getText()).doesNotContain("-");

            view.updateBasket(List.of(new LineItem(WIDGET, 1)),
                    new BigDecimal("10.00"),
                    new BigDecimal("2.00"),
                    BigDecimal.ZERO,
                    new BigDecimal("8.00"));
            assertThat(view.getDiscountValueForTest().getForeground()).isEqualTo(PosTheme.GO);
            assertThat(view.getDiscountValueForTest().getText()).startsWith("-");
        } finally {
            view.dispose();
        }
    }

    @Test
    void summaryHeightUnchangedBetweenZeroAndNonZeroDiscount() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            view.setSize(new Dimension(1412, 882));
            view.updateBasket(List.of(new LineItem(WIDGET, 1)),
                    new BigDecimal("10.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    new BigDecimal("10.00"));
            Dimension zeroSize = view.getSummaryTapeForTest().getPreferredSize();

            view.updateBasket(List.of(new LineItem(WIDGET, 1)),
                    new BigDecimal("10.00"),
                    new BigDecimal("2.00"),
                    BigDecimal.ZERO,
                    new BigDecimal("8.00"));
            Dimension nonZeroSize = view.getSummaryTapeForTest().getPreferredSize();

            assertThat(nonZeroSize.height).isEqualTo(zeroSize.height);
        } finally {
            view.dispose();
        }
    }

    @Test
    void totalRowValueRendersLargerThanComponentRows() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            float totalSize = view.getTotalValueForTest().getFont().getSize2D();
            float subtotalSize = view.getSubtotalValueForTest().getFont().getSize2D();
            assertThat(totalSize).isGreaterThan(subtotalSize);
        } finally {
            view.dispose();
        }
    }

    @Test
    void amountDueTracksTotal_afterEveryUpdate() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            view.updateBasket(List.of(new LineItem(WIDGET, 1)),
                    new BigDecimal("10.00"),
                    new BigDecimal("2.00"),
                    new BigDecimal("0.80"),
                    new BigDecimal("8.80"));
            assertThat(view.getAmountDueValueForTest().getText())
                    .isEqualTo(view.getTotalValueForTest().getText());

            view.updateBasket(new ArrayList<>(), BigDecimal.ZERO);
            assertThat(view.getAmountDueValueForTest().getText())
                    .isEqualTo(view.getTotalValueForTest().getText());
        } finally {
            view.dispose();
        }
    }

    @Test
    void taxLabelReflectsTransactionRate_andChangesWhenTheRateChanges() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            view.setTaxRate(new BigDecimal("0.07"));
            // 0.07 renders as 7, never 7.00.
            assertThat(view.getTaxLabelForTest().getText()).isEqualTo("Tax (7%)");

            view.setTaxRate(new BigDecimal("0.085"));
            assertThat(view.getTaxLabelForTest().getText()).isEqualTo("Tax (8.5%)");
        } finally {
            view.dispose();
        }
    }

    @Test
    void discountLabelCarriesTheCount_bareAtZero() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            view.setDiscountCount(2);
            assertThat(view.getDiscountLabelForTest().getText()).isEqualTo("Discount (2)");
            view.setDiscountCount(0);
            assertThat(view.getDiscountLabelForTest().getText()).isEqualTo("Discount");
        } finally {
            view.dispose();
        }
    }

    @Test
    void summaryShowsDiscountTotal_butNoPerDiscountDetailLines() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            view.updateBasket(List.of(new LineItem(WIDGET, 1)),
                    new BigDecimal("10.00"),
                    new BigDecimal("2.00"),
                    new BigDecimal("0.56"),
                    new BigDecimal("8.56"));
            // The combined discount total still renders in the tape.
            assertThat(view.getDiscountValueForTest().getText()).isEqualTo("-$2.00");
            // Per-discount description lines are gone: the summary body holds only the tape, with
            // no extra label rows beneath it.
            assertThat(view.getSummaryTapeForTest().getParent().getComponentCount()).isEqualTo(1);
        } finally {
            view.dispose();
        }
    }

    @Test
    void subtotalLabelIncludesItemCount() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("1.00"));
            assertThat(view.getSubtotalLabelForTest().getText()).contains("1 item");
            view.updateBasket(List.of(new LineItem(WIDGET, 3)), new BigDecimal("3.00"));
            assertThat(view.getSubtotalLabelForTest().getText()).contains("3 items");
        } finally {
            view.dispose();
        }
    }

    // Walks the summary tape in document order and returns the leftmost (label) JLabel per row.
    // The Total label sits under an EYEBROW font whose text is set sentence-case, but Swing
    // renders uppercase via the font's tracking — the source string here is what we set, and
    // for the Total row we deliberately set "TOTAL" to keep it distinct from the component
    // rows in the ordering assertion.
    private static List<String> summaryLabelsInOrder(CustomerView view) {
        JPanel tape = view.getSummaryTapeForTest();
        List<String> out = new ArrayList<>();
        for (Component c : tape.getComponents()) {
            if (!(c instanceof JPanel p)) continue;
            // Row panels use BorderLayout; the hairline separator is also a JPanel but with a
            // FlowLayout — skip anything that isn't a Border-laid row.
            if (!(p.getLayout() instanceof java.awt.BorderLayout bl)) continue;
            Component west = bl.getLayoutComponent(java.awt.BorderLayout.WEST);
            if (west instanceof JLabel label) out.add(label.getText());
        }
        return out;
    }

    private static IPosEventDispatcher noop() {
        return e -> {};
    }
}
