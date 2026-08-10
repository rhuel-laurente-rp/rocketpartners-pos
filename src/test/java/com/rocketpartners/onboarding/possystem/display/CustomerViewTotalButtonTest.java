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
 * Enable rule for the Total button: two gates in the same shape as Void basket.
 * The phase gate (basket-input enabled) says the transaction is in IN_PROGRESS; the content
 * gate says there is something to total (at least one non-voided line). Either off → disabled.
 */
class CustomerViewTotalButtonTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("10.00"));

    @Test
    void total_disabledOnFreshView_beforeAnyItems() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            assertThat(view.isTotalEnabled()).isFalse();
        } finally {
            view.dispose();
        }
    }

    @Test
    void total_disabled_whenBasketBecomesEmpty() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            view.setBasketInputEnabled(true);
            view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));
            assertThat(view.isTotalEnabled()).isTrue();

            view.updateBasket(List.of(), BigDecimal.ZERO);
            assertThat(view.isTotalEnabled()).isFalse();
        } finally {
            view.dispose();
        }
    }

    @Test
    void total_disabled_whenAllLinesVoided() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            view.setBasketInputEnabled(true);
            LineItem voided = new LineItem(WIDGET, 1);
            voided.setVoided(true);
            view.updateBasket(List.of(voided), BigDecimal.ZERO);
            assertThat(view.isTotalEnabled()).isFalse();
        } finally {
            view.dispose();
        }
    }

    @Test
    void total_disabled_afterTotalPress_evenWithLines() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            view.setBasketInputEnabled(true);
            view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));
            assertThat(view.isTotalEnabled()).isTrue();

            view.setBasketInputEnabled(false); // simulates Total press
            assertThat(view.isTotalEnabled()).isFalse();
        } finally {
            view.dispose();
        }
    }

    private static IPosEventDispatcher noop() {
        return e -> {};
    }
}
