package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.commons.model.TenderType;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.possystem.component.IController;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.service.TransactionService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles user input from {@link CustomerView} and mirrors {@link TransactionService} state back
 * to it. No Swing rendering: the view paints, the controller decides.
 *
 * <p>Subscribes to the seven view-input event types (four basket inputs — {@link
 * PosEventType#QUICK_ADD_PRESSED}, {@link PosEventType#VOID_LINE_PRESSED}, {@link
 * PosEventType#VOID_BASKET_PRESSED}, {@link PosEventType#TOTAL_PRESSED} — plus three tender
 * inputs — {@link PosEventType#TENDER_CASH_PRESSED}, {@link PosEventType#TENDER_DEBIT_PRESSED},
 * {@link PosEventType#TENDER_CREDIT_PRESSED}). Each handler invokes the appropriate
 * {@link TransactionService} call, dispatches the corresponding past-tense notification event
 * ({@link PosEventType#ITEM_ADDED}, etc.), and re-renders the view.</p>
 *
 * <p>After any terminal transition — {@link PosEventType#VOID_BASKET_PRESSED} or any tender —
 * the controller opens a fresh transaction so the next customer can be rung up without a
 * restart. This is standard POS behavior: a cashier voids or completes a sale and the terminal
 * is immediately ready for the next one.</p>
 *
 * <p>Service calls that throw are swallowed at this layer — the service has already dispatched
 * an {@link PosEventType#ERROR} event and the view has not yet been updated for the failed
 * operation, so the display stays consistent with the transaction. The controller does not
 * re-throw, so a bad UPC or an illegal state cannot kill the Swing event loop.</p>
 */
public class CustomerViewController implements IController, IPosEventListener {

    private static final Set<PosEventType> LISTEN_TYPES = Collections.unmodifiableSet(EnumSet.of(
            PosEventType.QUICK_ADD_PRESSED,
            PosEventType.VOID_LINE_PRESSED,
            PosEventType.VOID_BASKET_PRESSED,
            PosEventType.TOTAL_PRESSED,
            PosEventType.TENDER_CASH_PRESSED,
            PosEventType.TENDER_DEBIT_PRESSED,
            PosEventType.TENDER_CREDIT_PRESSED));

    private final CustomerView view;
    private PosComponent parent;

    /**
     * @param view the view this controller drives; must not be {@code null}
     */
    public CustomerViewController(CustomerView view) {
        if (view == null) throw new IllegalArgumentException("view must not be null");
        this.view = view;
    }

    // ---- IController ------------------------------------------------------

    @Override
    public void onStart(PosComponent parent) {
        this.parent = parent;
        parent.register(this);
        beginNewTransaction();
        view.setVisible(true);
    }

    @Override
    public void onEnd() {
        if (parent != null) {
            parent.unregister(this);
            parent = null;
        }
        view.dispose();
    }

    // ---- IPosEventListener ------------------------------------------------

    @Override
    public Set<PosEventType> getListeningEventTypes() {
        return LISTEN_TYPES;
    }

    @Override
    public void onPosEvent(PosEvent event) {
        switch (event.getType()) {
            case QUICK_ADD_PRESSED -> handleQuickAdd(event);
            case VOID_LINE_PRESSED -> handleVoidLine(event);
            case VOID_BASKET_PRESSED -> handleVoidBasket();
            case TOTAL_PRESSED -> handleTotal();
            case TENDER_CASH_PRESSED -> handleTenderCash();
            case TENDER_DEBIT_PRESSED -> handleTenderCard(TenderType.DEBIT);
            case TENDER_CREDIT_PRESSED -> handleTenderCard(TenderType.CREDIT);
            default -> { /* not subscribed */ }
        }
    }

    // ---- Handlers ---------------------------------------------------------

    private void handleQuickAdd(PosEvent event) {
        String upc = event.getProperty("upc", String.class);
        if (upc == null) return;
        LineItem added;
        try {
            added = parent.getTransactionService().addItemByUpc(upc, 1);
        } catch (RuntimeException ignored) {
            return;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("lineItem", added);
        parent.dispatchPosEvent(new PosEvent(PosEventType.ITEM_ADDED, props));
        render();
    }

    private void handleVoidLine(PosEvent event) {
        LineItem selected = event.getProperty("lineItem", LineItem.class);
        if (selected == null) return;
        try {
            parent.getTransactionService().voidLine(selected);
        } catch (RuntimeException ignored) {
            return;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("lineItem", selected);
        parent.dispatchPosEvent(new PosEvent(PosEventType.LINE_VOIDED, props));
        render();
    }

    private void handleVoidBasket() {
        try {
            parent.getTransactionService().voidBasket();
        } catch (RuntimeException ignored) {
            return;
        }
        parent.dispatchPosEvent(new PosEvent(PosEventType.BASKET_VOIDED));
        beginNewTransaction();
    }

    private void handleTotal() {
        try {
            parent.getTransactionService().total();
        } catch (RuntimeException ignored) {
            return;
        }
        parent.dispatchPosEvent(new PosEvent(PosEventType.TRANSACTION_TOTALED));
        view.setBasketInputEnabled(false);
        view.setTenderInputEnabled(true);
        render();
    }

    private void handleTenderCash() {
        try {
            parent.getTransactionService().tenderPayNextDollar();
        } catch (RuntimeException ignored) {
            return;
        }
        parent.dispatchPosEvent(new PosEvent(PosEventType.CASH_TENDERED));
        parent.dispatchPosEvent(new PosEvent(PosEventType.TRANSACTION_COMPLETED));
        beginNewTransaction();
    }

    private void handleTenderCard(TenderType tenderType) {
        Transaction tx = parent.getTransactionService().getCurrentTransaction();
        if (tx == null) return;
        BigDecimal amount = tx.grandTotal();
        try {
            parent.getTransactionService().tenderCard(tenderType, amount);
        } catch (RuntimeException ignored) {
            return;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("tenderType", tenderType);
        parent.dispatchPosEvent(new PosEvent(PosEventType.CARD_TENDERED, props));
        parent.dispatchPosEvent(new PosEvent(PosEventType.TRANSACTION_COMPLETED));
        beginNewTransaction();
    }

    // ---- State transitions ------------------------------------------------

    private void beginNewTransaction() {
        try {
            parent.getTransactionService().startTransaction();
        } catch (RuntimeException ignored) {
            // Service dispatched ERROR; render whatever it left behind.
        }
        view.setBasketInputEnabled(true);
        view.setTenderInputEnabled(false);
        render();
    }

    private void render() {
        Transaction tx = parent.getTransactionService().getCurrentTransaction();
        if (tx == null) {
            view.updateBasket(List.of(), BigDecimal.ZERO);
            return;
        }
        // Copy the aggregate's line-item list so the view (and any observer, such as a
        // Mockito verify) sees a snapshot that reflects the exact state at render time.
        view.updateBasket(new ArrayList<>(tx.getLineItems()), tx.subtotal());
    }
}
