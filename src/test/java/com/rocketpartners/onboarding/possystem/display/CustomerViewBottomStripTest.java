package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Real-Swing tests pinning the enable rules of the restructured bottom strip — the actions row
 * (Void Basket · Void Line · Change Qty · Discount · Total) and the tender row (Pay Cash · Pay
 * Debit · Pay Credit). The restructure from stacked rows to single rows must not change which
 * control is live in which phase; these assertions describe that contract independent of layout.
 *
 * <p>Selection-dependent rules (Change Qty / Void Line) and the Void Basket / Total content-and-
 * phase gates have their own dedicated tests; this class covers the two rules not asserted
 * elsewhere — Discount stays disabled, and tender is gated on {@link
 * CustomerView#setTenderInputEnabled(boolean)} — plus a phase-crossing sanity check that Total's
 * disablement at TOTALED does not drag Void Basket down with it.</p>
 */
class CustomerViewBottomStripTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("10.00"));

    @Test
    void discount_staysDisabled_beforeAndAfterTotal() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));
        assertThat(view.isDiscountEnabledForTest()).isFalse();

        // Simulate the Total press: basket input off, tender on. Discount must still be dark.
        view.setBasketInputEnabled(false);
        view.setTenderInputEnabled(true);
        assertThat(view.isDiscountEnabledForTest()).isFalse();
    }

    @Test
    void tender_disabledByDefault_enabledOnlyWhenTenderInputOn() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));
        assertThat(view.isTenderEnabledForTest()).isFalse();

        view.setTenderInputEnabled(true);
        assertThat(view.isTenderEnabledForTest()).isTrue();

        view.setTenderInputEnabled(false);
        assertThat(view.isTenderEnabledForTest()).isFalse();
    }

    @Test
    void total_disabledAtTotaled_butVoidBasketStaysEnabled() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));
        assertThat(view.isTotalEnabled()).isTrue();
        assertThat(view.isVoidBasketEnabled()).isTrue();

        // TOTALED: basket input off (Total dark) but lifecycle still on (Void Basket live), because
        // the domain legalises voidBasket() in TOTALED and a customer can still change their mind.
        view.setBasketInputEnabled(false);
        assertThat(view.isTotalEnabled()).isFalse();
        assertThat(view.isVoidBasketEnabled()).isTrue();
    }

    private static IPosEventDispatcher noop() {
        return event -> {};
    }
}
