package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.commons.model.TransactionState;
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
 * <p>Subscribes to the three basket-input event types ({@link PosEventType#QUICK_ADD_PRESSED},
 * {@link PosEventType#VOID_LINE_PRESSED}, {@link PosEventType#TOTAL_PRESSED}) plus the
 * confirmed void-basket event ({@link PosEventType#VOID_BASKET_CONFIRM_PRESSED}) and
 * {@link PosEventType#RECEIPT_DISMISSED} as a lifecycle signal. The three tender-input events
 * belong to child controllers ({@link PayWithCashViewController},
 * {@link PayWithCardViewController}); this controller doesn't tender itself. The
 * initial-press event {@link PosEventType#VOID_BASKET_PRESSED} belongs to
 * {@link VoidBasketConfirmViewController}, which opens the confirmation dialog. Voiding is
 * only committed once the cashier confirms — this controller reacts to that second event.</p>
 *
 * <p>After any terminal transition — {@link PosEventType#VOID_BASKET_CONFIRM_PRESSED} or a
 * tender followed by receipt dismissal (surfaced via {@link PosEventType#RECEIPT_DISMISSED}) —
 * the controller opens a fresh transaction so the next customer can be rung up without a
 * restart. Waiting for {@code RECEIPT_DISMISSED} rather than {@code TRANSACTION_COMPLETED}
 * ensures the cashier sees the receipt before the display flips back to an empty basket.</p>
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
            // VOID_BASKET_PRESSED belongs to VoidBasketConfirmViewController — that controller
            // opens the confirmation dialog. This controller waits for the second, confirmed
            // event before committing the void.
            PosEventType.VOID_BASKET_CONFIRM_PRESSED,
            PosEventType.TOTAL_PRESSED,
            PosEventType.ITEM_SCANNED,
            // Re-render whenever a peer controller has mutated the basket (e.g. the
            // change-qty dialog changed a quantity or voided a line via the zero path).
            PosEventType.QUANTITY_CHANGED,
            PosEventType.LINE_VOIDED,
            PosEventType.RECEIPT_DISMISSED));

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
            case ITEM_SCANNED -> handleScannedItem(event);
            case VOID_LINE_PRESSED -> handleVoidLine(event);
            case VOID_BASKET_CONFIRM_PRESSED -> handleVoidBasketConfirmed();
            case TOTAL_PRESSED -> handleTotal();
            case QUANTITY_CHANGED, LINE_VOIDED -> render();
            case RECEIPT_DISMISSED -> beginNewTransaction();
            default -> { /* not subscribed */ }
        }
    }

    // ---- Handlers ---------------------------------------------------------

    private void handleQuickAdd(PosEvent event) {
        addItemByUpc(event.getProperty("upc", String.class));
    }

    private void handleScannedItem(PosEvent event) {
        addItemByUpc(event.getProperty("upc", String.class));
    }

    private void addItemByUpc(String upc) {
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
        // Re-render happens via the LINE_VOIDED subscription — no need to call render()
        // directly, and doing so would double-render (mocked view expectations would break).
        parent.dispatchPosEvent(new PosEvent(PosEventType.LINE_VOIDED, props));
    }

    private void handleVoidBasketConfirmed() {
        Transaction tx = parent.getTransactionService().getCurrentTransaction();
        if (tx == null) return;
        // Snapshot BEFORE voidBasket() — the aggregate transitions to VOIDED which zeroes
        // subtotal, and the "prior state" for journalling is only interesting because we
        // remembered it here. Voiding after Total is the more expensive path operationally.
        int itemCount = 0;
        for (LineItem li : tx.getLineItems()) {
            if (!li.isVoided()) itemCount += li.getQuantity();
        }
        BigDecimal grandTotal = tx.grandTotal();
        TransactionState priorState = tx.getState();

        try {
            parent.getTransactionService().voidBasket();
        } catch (RuntimeException ignored) {
            return;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("itemCount", itemCount);
        props.put("grandTotal", grandTotal);
        props.put("priorState", priorState.name());
        parent.dispatchPosEvent(new PosEvent(PosEventType.BASKET_VOIDED, props));
        // Reuse the same reset path a dismissed receipt takes so a voided lane lands in the same
        // usable idle state — fresh transaction, basket cleared, tender disabled, scan focus
        // restored by ScannerViewController which already listens on BASKET_VOIDED.
        beginNewTransaction();
    }

    private void handleTotal() {
        try {
            parent.getTransactionService().total();
        } catch (RuntimeException ignored) {
            return;
        }
        parent.dispatchPosEvent(new PosEvent(PosEventType.TRANSACTION_TOTALED));
        // At TOTALED the domain freezes basket mutation but still permits voiding the whole
        // transaction — a customer changing their mind at the card reader must still be able to
        // walk away. Basket input off; lifecycle input on.
        view.setBasketInputEnabled(false);
        view.setLifecycleInputEnabled(true);
        view.setTenderInputEnabled(true);
        render();
    }

    // ---- State transitions ------------------------------------------------

    private void beginNewTransaction() {
        try {
            parent.getTransactionService().startTransaction();
        } catch (RuntimeException ignored) {
            // Service dispatched ERROR; render whatever it left behind.
        }
        // IN_PROGRESS: both basket mutation and voiding are legal. The Void basket button is
        // additionally gated on a non-empty basket by CustomerView#refreshVoidBasketButton so
        // it stays disabled until the first item is rung up.
        view.setBasketInputEnabled(true);
        view.setLifecycleInputEnabled(true);
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
        // Mockito verify) sees a snapshot that reflects the exact state at render time. Feed
        // the full breakdown — subtotal, discount, tax, grand total — so the inline summary
        // strip renders live tax instead of always $0.00.
        view.updateBasket(
                new ArrayList<>(tx.getLineItems()),
                tx.subtotal(),
                tx.discountTotal(),
                tx.taxTotal(),
                tx.grandTotal());
    }
}
