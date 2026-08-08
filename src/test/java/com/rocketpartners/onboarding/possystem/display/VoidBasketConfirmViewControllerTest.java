package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.repository.inmemory.InMemoryItemRepository;
import com.rocketpartners.onboarding.possystem.service.TaxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Controller-level tests for the void-basket confirmation flow: the initial press opens the
 * dialog and does <em>not</em> void anything on its own; the confirm and decline outcomes
 * dispatch the expected events.
 */
class VoidBasketConfirmViewControllerTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("10.00"));

    private PosComponent pos;
    private VoidBasketConfirmView view;
    private VoidBasketConfirmViewController controller;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);
        pos = new PosComponent(
                new InMemoryItemRepository(items),
                new TaxService(BigDecimal.ZERO),
                "Test Store",
                1,
                false);
        SwingUtilities.invokeAndWait(() -> {
            view = new VoidBasketConfirmView(null, pos);
            // PosDialog is modal; letting a real modal dialog open during a test stalls the
            // build. Force non-modal so setVisible(true) returns immediately and the
            // controller-level assertions still see the primed dialog state.
            view.setModal(false);
        });
        controller = new VoidBasketConfirmViewController(view);
        pos.addController(controller);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (view != null) {
            SwingUtilities.invokeAndWait(() -> {
                view.setVisible(false);
                view.dispose();
            });
        }
    }

    @Test
    void voidBasketPressed_opensDialog_andDoesNotVoidAnything() {
        pos.start();
        pos.getTransactionService().startTransaction();
        pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), 1);
        var tx = pos.getTransactionService().getCurrentTransaction();

        pos.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_PRESSED));

        assertThat(view.isVisible()).as("the confirmation dialog must be showing").isTrue();
        // Transaction untouched — pressing the button alone must not mutate state.
        assertThat(pos.getTransactionService().getCurrentTransaction()).isSameAs(tx);
        assertThat(tx.getState().name()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void voidBasketPressed_populatesDialogWithCurrentCountAndTotal() {
        pos.start();
        pos.getTransactionService().startTransaction();
        pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), 3);

        pos.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_PRESSED));

        assertThat(view.getItemCountForTest()).isEqualTo(3);
        assertThat(view.getGrandTotalForTest())
                .isEqualByComparingTo(new BigDecimal("30.00"));
    }

    @Test
    void voidBasketPressedOnEmptyBasket_isNoOp_dialogNotShown() {
        // The Void basket button is disabled on an empty basket by CustomerView, so a real
        // press cannot land here. A stale event from a race must still leave the dialog closed
        // rather than opening it with an empty summary.
        pos.start();
        pos.getTransactionService().startTransaction();

        pos.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_PRESSED));

        assertThat(view.isVisible()).isFalse();
    }

    @Test
    void confirmEvent_closesTheDialog() {
        pos.start();
        pos.getTransactionService().startTransaction();
        pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), 1);
        pos.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_PRESSED));
        assertThat(view.isVisible()).isTrue();

        pos.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_CONFIRM_PRESSED));

        assertThat(view.isVisible()).isFalse();
    }

    @Test
    void declineEvent_closesTheDialog() {
        pos.start();
        pos.getTransactionService().startTransaction();
        pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), 1);
        pos.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_PRESSED));

        pos.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_DECLINED));

        assertThat(view.isVisible()).isFalse();
    }
}
