package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Real-Swing tests for the Change Qty button's enable state. Skipped in headless CI
 * environments — the JFrame constructor requires a display.
 */
class CustomerViewChangeQtyButtonTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("10.00"));

    @Test
    void changeQty_disabled_withNoSelection() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        // Empty basket — nothing to select, so the button must be off.
        view.updateBasket(List.of(), BigDecimal.ZERO);

        assertThat(view.isChangeQtyEnabled()).isFalse();
    }

    @Test
    void changeQty_disabled_onVoidedLine() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        LineItem voided = new LineItem(WIDGET, 1);
        voided.setVoided(true);
        view.updateBasket(List.of(voided), BigDecimal.ZERO);

        // Even if we set selection on a voided line, Change Qty must stay off.
        selectFirst(view);

        assertThat(view.isChangeQtyEnabled()).isFalse();
    }

    @Test
    void changeQty_disabled_whenBasketInputDisabled_totalPressed() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));
        selectFirst(view);
        view.setBasketInputEnabled(false); // simulates Total press

        assertThat(view.isChangeQtyEnabled()).isFalse();
    }

    @Test
    void changeQty_enabled_whenNonVoidedRowSelected_andBasketInputOn() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noopDispatcher());
        view.updateBasket(List.of(new LineItem(WIDGET, 1)), new BigDecimal("10.00"));
        view.setBasketInputEnabled(true);
        selectFirst(view);

        assertThat(view.isChangeQtyEnabled()).isTrue();
    }

    private static void selectFirst(CustomerView view) {
        java.awt.Container content = view.getContentPane();
        javax.swing.JList<?> list = findList(content);
        if (list != null) list.setSelectedIndex(0);
    }

    private static javax.swing.JList<?> findList(java.awt.Container c) {
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof javax.swing.JList<?> l) return l;
            if (child instanceof java.awt.Container inner) {
                javax.swing.JList<?> found = findList(inner);
                if (found != null) return found;
            }
            if (child instanceof javax.swing.JScrollPane sp
                    && sp.getViewport().getView() instanceof javax.swing.JList<?> l) {
                return l;
            }
        }
        return null;
    }

    private static IPosEventDispatcher noopDispatcher() {
        return event -> {};
    }
}
