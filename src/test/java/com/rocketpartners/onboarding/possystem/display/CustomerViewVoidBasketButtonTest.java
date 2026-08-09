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
 * The enable rule for the Void basket button, verified as a regression test against the reported
 * bug: pressing Total must not disable Void basket, because the domain still permits it.
 */
class CustomerViewVoidBasketButtonTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("10.00"));

    @Test
    void voidBasket_enabled_inProgressWithAtLeastOneNonVoidedLine() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        view.setBasketInputEnabled(true);
        view.setLifecycleInputEnabled(true);
        view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));

        assertThat(view.isVoidBasketEnabled()).isTrue();
    }

    @Test
    void voidBasket_stillEnabled_afterTotal_isTheRegressionTest() {
        // The reported bug: pressing Total disabled Void basket alongside the mutation controls.
        // TOTALED still permits voidBasket() in the domain — the button must stay live so the
        // cashier can abandon the sale when a customer changes their mind at the card reader.
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));
        view.setBasketInputEnabled(false); // simulates Total press
        view.setLifecycleInputEnabled(true);

        assertThat(view.isVoidBasketEnabled())
                .as("Void basket must remain enabled after Total — matches the domain rule")
                .isTrue();
    }

    @Test
    void voidBasket_disabled_whenNoNonVoidedLineItems() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        view.setBasketInputEnabled(true);
        view.setLifecycleInputEnabled(true);
        view.updateBasket(List.of(), BigDecimal.ZERO);

        assertThat(view.isVoidBasketEnabled())
                .as("nothing to discard, and the confirmation dialog would open on an empty summary")
                .isFalse();
    }

    @Test
    void voidBasket_disabled_whenEveryLineIsVoided() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        view.setBasketInputEnabled(true);
        view.setLifecycleInputEnabled(true);
        LineItem voided = new LineItem(WIDGET, 1);
        voided.setVoided(true);
        view.updateBasket(List.of(voided), BigDecimal.ZERO);

        assertThat(view.isVoidBasketEnabled()).isFalse();
    }

    @Test
    void voidBasket_disabled_inPaidOrVoidedTerminalStates() {
        // PAID and VOIDED are terminal — no further transitions are legal. The controller signals
        // this to the view by turning lifecycle input off. Basket state (empty or not) does not
        // matter here; the lifecycle flag alone must gate the button.
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));

        view.setLifecycleInputEnabled(false);
        assertThat(view.isVoidBasketEnabled()).isFalse();
    }

    @Test
    void setBasketInputEnabledFalse_doesNotDisableVoidBasket() {
        // The specific behavioural fix: setBasketInputEnabled MUST NOT touch voidBasketButton.
        // Verified by driving the enable flag independently and observing Void basket stays on.
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));
        view.setLifecycleInputEnabled(true);

        view.setBasketInputEnabled(false);

        assertThat(view.isVoidBasketEnabled()).isTrue();
    }

    @Test
    void atTotaled_basketInputControls_areAllDisabled() {
        // The other side of the split: quick-add / change qty / void line / total all disable
        // when setBasketInputEnabled(false) is called. Change qty and void line additionally
        // require a selection, so they're always off with no selection — but Total specifically
        // is disabled by the basket-input flag alone.
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));

        view.setBasketInputEnabled(false);

        assertThat(view.isTotalEnabled()).isFalse();
        assertThat(view.isChangeQtyEnabled()).isFalse();
        assertThat(view.isVoidLineEnabled()).isFalse();
    }

    @Test
    void getBasketItemCount_returnsSumOfQuantities_notLineCount() {
        // One line at quantity 12 is still twelve items — this is the count the confirmation
        // dialog should show, and the controller computes it independently, but the view also
        // exposes it so both routes agree.
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        view.updateBasket(List.of(new LineItem(WIDGET, 12)), new BigDecimal("120.00"));

        assertThat(view.getBasketItemCount()).isEqualTo(12);
    }

    @Test
    void getBasketItemCount_excludesVoidedLines() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        LineItem voided = new LineItem(WIDGET, 5);
        voided.setVoided(true);
        LineItem live = new LineItem(WIDGET, 2);
        view.updateBasket(List.of(voided, live), new BigDecimal("20.00"));

        assertThat(view.getBasketItemCount())
                .as("only non-voided quantities count — the receipt only shows non-voided lines")
                .isEqualTo(2);
    }

    private static IPosEventDispatcher noopDispatcher() {
        return event -> {};
    }
}
