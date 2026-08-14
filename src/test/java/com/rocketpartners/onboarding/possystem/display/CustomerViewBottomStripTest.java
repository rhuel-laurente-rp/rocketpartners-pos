package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.util.ArrayList;
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
 * elsewhere — Discount follows the basket-input phase (live IN_PROGRESS, dark at TOTALED), and
 * tender is gated on {@link CustomerView#setTenderInputEnabled(boolean)} — plus a phase-crossing
 * sanity check that Total's disablement at TOTALED does not drag Void Basket down with it.</p>
 */
class CustomerViewBottomStripTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("10.00"));

    @Test
    void discount_enabledInProgress_disabledAtTotaled() {
        // This assertion changed with feature/discount-engine-core: the Discount button used to be
        // permanently disabled (feature not landed). Wiring the eligibility-discount dialog is the
        // point of that branch, so the button is now live IN_PROGRESS and dark once the basket is
        // frozen at Total — the same phase gate as the other basket-mutation actions.
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));
        assertThat(view.isDiscountEnabledForTest()).isTrue();

        // Simulate the Total press: basket input off, tender on. Discount goes dark with the rest
        // of the basket-mutation actions — eligibility selection is an IN_PROGRESS concern.
        view.setBasketInputEnabled(false);
        view.setTenderInputEnabled(true);
        assertThat(view.isDiscountEnabledForTest()).isFalse();
    }

    @Test
    void discount_disabledWhenBasketEmpty_evenInProgress() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        // Fresh, IN_PROGRESS, empty basket: nothing to discount yet, so Discount is dark.
        assertThat(view.isDiscountEnabledForTest()).isFalse();

        // First item rung up: Discount lights up.
        view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));
        assertThat(view.isDiscountEnabledForTest()).isTrue();

        // Basket emptied again: back to dark.
        view.updateBasket(new java.util.ArrayList<>(), BigDecimal.ZERO);
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

    @Test
    void resumeButton_visibleOnlyAtTotaled() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));
        // IN_PROGRESS: the order is already editable, so the recall control is hidden.
        assertThat(view.isResumeVisibleForTest()).isFalse();

        // TOTALED: basket input off, lifecycle still on -> the "Add Item" control appears.
        view.setBasketInputEnabled(false);
        assertThat(view.isResumeVisibleForTest()).isTrue();

        // Re-opened -> hidden again.
        view.setBasketInputEnabled(true);
        assertThat(view.isResumeVisibleForTest()).isFalse();
    }

    @Test
    void resumeButton_dispatchesResumeEditing() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        List<PosEvent> events = new ArrayList<>();
        CustomerView view = new CustomerView("test", List.of(), events::add);
        view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));
        view.setBasketInputEnabled(false); // TOTALED -> button visible
        view.getResumeButtonForTest().doClick();
        assertThat(events).anyMatch(e -> e.getType() == PosEventType.RESUME_EDITING_PRESSED);
    }

    private static IPosEventDispatcher noop() {
        return event -> {};
    }
}
