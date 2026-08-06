package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.commons.model.TransactionState;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.repository.inmemory.InMemoryItemRepository;
import com.rocketpartners.onboarding.possystem.service.TaxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorPopupViewControllerTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("10.00"));

    private PosComponent pos;
    private CapturingPresenter presenter;
    private SynchronousInvoker invoker;
    private AtomicInteger dismissCount;
    private ErrorPopupViewController controller;

    @BeforeEach
    void setUp() {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);
        pos = new PosComponent(
                new InMemoryItemRepository(items),
                new TaxService(BigDecimal.ZERO),
                "Test Store",
                1,
                false);
        presenter = new CapturingPresenter();
        invoker = new SynchronousInvoker();
        dismissCount = new AtomicInteger();
        controller = new ErrorPopupViewController(
                null, invoker, presenter, dismissCount::incrementAndGet);
        pos.addController(controller);
        pos.start();
    }

    @Test
    void upcNotFoundError_mapsToItemNotFoundMessage() {
        pos.dispatchPosEvent(error("UPC_NOT_FOUND", "unknown UPC: 012345678905", "upc", "012345678905"));

        assertThat(presenter.messages).containsExactly("Item not found: 012345678905");
    }

    @Test
    void invalidCashAmount_mapsToCashierReadableMessage() {
        pos.dispatchPosEvent(error("INVALID_CASH_AMOUNT", "cash received is not a valid number: banana"));

        assertThat(presenter.messages)
                .containsExactly("Invalid cash amount. Enter a valid, non-negative number.");
    }

    @Test
    void underpayment_mapsToCashierReadableMessage() {
        pos.dispatchPosEvent(error("UNDERPAYMENT", "cash received 5.00 is less than amount due 7.30"));

        assertThat(presenter.messages)
                .containsExactly("Cash received is less than the amount due.");
    }

    @Test
    void totaledInvariant_mapsToActionIllegalMessage() {
        pos.dispatchPosEvent(error("TOTALED_INVARIANT",
                "operation 'addLineItem' is not legal in state TOTALED"));

        assertThat(presenter.messages).containsExactly("That action isn't allowed right now.");
    }

    @Test
    void noTransaction_mapsToActionIllegalMessage() {
        pos.dispatchPosEvent(error("NO_TRANSACTION", "no transaction is open"));

        assertThat(presenter.messages).containsExactly("That action isn't allowed right now.");
    }

    @Test
    void invalidArgument_mapsToInvalidInputWithDetail() {
        pos.dispatchPosEvent(error("INVALID_ARGUMENT", "quantity must be >= 1, got 0"));

        assertThat(presenter.messages)
                .containsExactly("Invalid input: quantity must be >= 1, got 0");
    }

    @Test
    void missingMessageProperty_yieldsGenericFallback_notNull() {
        // No message property, no code — the fallback path.
        pos.dispatchPosEvent(new PosEvent(PosEventType.ERROR, new HashMap<>()));

        assertThat(presenter.messages).hasSize(1);
        assertThat(presenter.messages.get(0)).isNotNull();
        assertThat(presenter.messages.get(0)).isEqualTo("An unexpected error occurred.");
        assertThat(presenter.messages.get(0)).doesNotContain("null");
    }

    @Test
    void unknownCodeWithMessage_showsMessageVerbatim() {
        pos.dispatchPosEvent(error("SOMETHING_NEW", "external system said no"));

        assertThat(presenter.messages).containsExactly("external system said no");
    }

    @Test
    void unknownCodeWithoutMessage_yieldsGenericFallback() {
        pos.dispatchPosEvent(error("SOMETHING_NEW", null));

        assertThat(presenter.messages).containsExactly("An unexpected error occurred.");
    }

    @Test
    void secondErrorDispatched_whileDialogOpen_isSuppressed() {
        // Use a queuing invoker so the presenter call is DEFERRED — imitates a modal dialog
        // the cashier hasn't dismissed yet. Then fire a second error and confirm the second
        // presenter call was never queued.
        QueuedInvoker queued = new QueuedInvoker();
        ErrorPopupViewController queueingController = new ErrorPopupViewController(
                null, queued, presenter, dismissCount::incrementAndGet);
        pos.removeController(this.controller);
        pos.addController(queueingController);

        pos.dispatchPosEvent(error("UPC_NOT_FOUND", "unknown UPC: 111", "upc", "111"));
        // First error queued; dialog "showing" (deferred callback not yet run).
        assertThat(queued.pending()).isEqualTo(1);
        assertThat(queueingController.isDialogShowing()).isTrue();

        // Fire two more before the first callback runs.
        pos.dispatchPosEvent(error("UPC_NOT_FOUND", "unknown UPC: 222", "upc", "222"));
        pos.dispatchPosEvent(error("UPC_NOT_FOUND", "unknown UPC: 333", "upc", "333"));

        // Second and third dropped: still exactly one pending Swing task.
        assertThat(queued.pending()).isEqualTo(1);

        // Now let the first one run.
        queued.runNext();
        assertThat(presenter.messages).containsExactly("Item not found: 111");
        assertThat(queueingController.isDialogShowing()).isFalse();
    }

    @Test
    void dialogFlagReleased_afterDismiss_soFutureErrorsShow() {
        pos.dispatchPosEvent(error("UPC_NOT_FOUND", "unknown UPC: 111", "upc", "111"));
        assertThat(presenter.messages).hasSize(1);
        assertThat(controller.isDialogShowing()).isFalse();

        pos.dispatchPosEvent(error("UPC_NOT_FOUND", "unknown UPC: 222", "upc", "222"));

        assertThat(presenter.messages).hasSize(2);
        assertThat(presenter.messages.get(1)).isEqualTo("Item not found: 222");
    }

    @Test
    void errorFromNonEdtThread_isMarshalledToInvoker() throws Exception {
        // Confirm the controller uses the invoker rather than running the presenter inline on
        // the dispatching thread. In production the invoker is SwingUtilities::invokeLater;
        // here the test invoker starts a fresh named thread.
        Thread callerThread = Thread.currentThread();
        List<Thread> executedOn = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        ErrorPopupViewController.EdtInvoker recordingInvoker = r -> {
            Thread t = new Thread(() -> {
                r.run();
                done.countDown();
            }, "sim-edt");
            t.setDaemon(true);
            executedOn.add(t);
            t.start();
        };
        ErrorPopupViewController threadedController = new ErrorPopupViewController(
                null, recordingInvoker, presenter, dismissCount::incrementAndGet);
        pos.removeController(this.controller);
        pos.addController(threadedController);

        // Dispatch from a background thread.
        Thread background = new Thread(() ->
                pos.dispatchPosEvent(error("UPC_NOT_FOUND", "unknown UPC: 999", "upc", "999")),
                "bg-caller");
        background.start();
        background.join(1000);

        assertThat(done.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(executedOn).hasSize(1);
        assertThat(executedOn.get(0).getName()).isEqualTo("sim-edt");
        assertThat(executedOn.get(0)).isNotSameAs(callerThread);
        assertThat(executedOn.get(0)).isNotSameAs(background);
        assertThat(presenter.messages).containsExactly("Item not found: 999");
    }

    @Test
    void dismissingError_invokesOnDismissHook_forFocusReturn() {
        pos.dispatchPosEvent(error("UPC_NOT_FOUND", "unknown UPC: 111", "upc", "111"));

        // In production this hook is `focusComponent::requestFocusInWindow` — running it here
        // proves the controller returns focus after dismiss, without asserting on Swing state.
        assertThat(dismissCount.get()).isEqualTo(1);
    }

    @Test
    void transactionState_isUnchanged_afterErrorDismissed() {
        // Ring up a widget, then trigger an error (unknown UPC). Verify the widget is still on
        // the transaction, the state is still IN_PROGRESS, and the subtotal unchanged.
        pos.getTransactionService().startTransaction();
        pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), 1);
        Transaction tx = pos.getTransactionService().getCurrentTransaction();
        BigDecimal subtotalBefore = tx.subtotal();
        TransactionState stateBefore = tx.getState();
        AtomicBoolean threwUnknownUpc = new AtomicBoolean();

        try {
            pos.getTransactionService().addItemByUpc("does-not-exist", 1);
        } catch (RuntimeException ignored) {
            threwUnknownUpc.set(true);
        }

        // The unknown-UPC path dispatched an ERROR through the service; the controller then
        // showed a popup. Neither of them touched the transaction.
        assertThat(threwUnknownUpc.get()).isTrue();
        assertThat(presenter.messages).containsExactly("Item not found: does-not-exist");
        assertThat(tx.getState()).isEqualTo(stateBefore);
        assertThat(tx.subtotal()).isEqualByComparingTo(subtotalBefore);
        assertThat(tx.getLineItems()).hasSize(1);
        assertThat(tx.getLineItems().get(0).getItem()).isEqualTo(WIDGET);
    }

    // ---- Helpers -----------------------------------------------------------

    private static PosEvent error(String code, String message) {
        Map<String, Object> props = new HashMap<>();
        if (code != null) props.put("code", code);
        if (message != null) props.put("message", message);
        return new PosEvent(PosEventType.ERROR, props);
    }

    private static PosEvent error(String code, String message, String extraKey, Object extraValue) {
        Map<String, Object> props = new HashMap<>();
        if (code != null) props.put("code", code);
        if (message != null) props.put("message", message);
        props.put(extraKey, extraValue);
        return new PosEvent(PosEventType.ERROR, props);
    }

    /** Synchronous EDT invoker: runs the task inline. Used for message-mapping tests. */
    static final class SynchronousInvoker implements ErrorPopupViewController.EdtInvoker {
        @Override
        public void invoke(Runnable r) {
            r.run();
        }
    }

    /** Queuing invoker: captures the task so the test can decide when it runs. */
    static final class QueuedInvoker implements ErrorPopupViewController.EdtInvoker {
        final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void invoke(Runnable r) {
            queue.add(r);
        }

        int pending() {
            return queue.size();
        }

        void runNext() {
            Runnable r = queue.pollFirst();
            if (r != null) r.run();
        }
    }

    /** Captures each message the controller would have shown to the cashier. */
    static final class CapturingPresenter implements ErrorPopupViewController.ErrorPresenter {
        final List<String> messages = new ArrayList<>();

        @Override
        public void show(Component parent, String title, String message) {
            messages.add(message);
        }
    }
}
