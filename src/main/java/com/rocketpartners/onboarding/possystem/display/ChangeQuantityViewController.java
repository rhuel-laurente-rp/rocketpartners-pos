package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.possystem.component.IController;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Owns the change-quantity flow. Opens a modal {@link ChangeQuantityView} when
 * {@link PosEventType#CHANGE_QTY_PRESSED} arrives with a selected line, and on Confirm calls
 * {@link com.rocketpartners.onboarding.possystem.service.TransactionService#updateLineItemQuantity(LineItem, int)}
 * — the same recompute path as add-item.
 *
 * <p><strong>No zero path.</strong> The dialog cannot emit a quantity below 1: its spinner
 * model floor is 1 and its digit-only editor rejects minus signs. Void Line is the sole way
 * to remove a line — two dialogs routing to the same terminal state would be two sets of bugs.
 * The service continues to validate independently, but the "changing to zero voids the line"
 * translation that used to live here is gone.</p>
 *
 * <p>All server-side validation lives in the service. This controller only opens the dialog,
 * forwards the chosen quantity, and dispatches {@link PosEventType#QUANTITY_CHANGED} (or
 * nothing, when the service rejected the input — the service has already dispatched an
 * ERROR).</p>
 *
 * <p><strong>Scanner suspension.</strong> Opening this dialog dispatches
 * {@code CHANGE_QTY_PRESSED}, which {@link ScannerViewController} listens for and treats as
 * a "suspend capture" signal — same rule that already applies to the cash and receipt
 * dialogs. Cancel/Confirm resume capture and the scan field regains focus.</p>
 */
public class ChangeQuantityViewController implements IController, IPosEventListener {

    private static final Set<PosEventType> LISTEN_TYPES = Collections.unmodifiableSet(EnumSet.of(
            PosEventType.CHANGE_QTY_PRESSED,
            PosEventType.CHANGE_QTY_CONFIRM_PRESSED,
            PosEventType.CHANGE_QTY_CANCEL_PRESSED));

    private final ChangeQuantityView view;
    private PosComponent parent;

    /**
     * @param view the modal change-quantity dialog this controller drives; must not be
     *             {@code null}
     */
    public ChangeQuantityViewController(ChangeQuantityView view) {
        if (view == null) throw new IllegalArgumentException("view must not be null");
        this.view = view;
    }

    // ---- IController ------------------------------------------------------

    @Override
    public void onStart(PosComponent parent) {
        this.parent = parent;
        parent.register(this);
    }

    @Override
    public void onEnd() {
        if (parent != null) {
            parent.unregister(this);
            parent = null;
        }
        view.closeDialog();
    }

    // ---- IPosEventListener ------------------------------------------------

    @Override
    public Set<PosEventType> getListeningEventTypes() {
        return LISTEN_TYPES;
    }

    @Override
    public void onPosEvent(PosEvent event) {
        switch (event.getType()) {
            case CHANGE_QTY_PRESSED -> openDialog(event);
            case CHANGE_QTY_CONFIRM_PRESSED -> confirm(event);
            case CHANGE_QTY_CANCEL_PRESSED -> view.closeDialog();
            default -> { /* not subscribed */ }
        }
    }

    private void openDialog(PosEvent event) {
        LineItem selected = event.getProperty("lineItem", LineItem.class);
        if (selected == null || selected.isVoided()) {
            // Selection was cleared or the line is already voided; the button should have
            // been disabled but a stale click could still land here. Do nothing.
            return;
        }
        view.openFor(selected);
    }

    private void confirm(PosEvent event) {
        LineItem selected = event.getProperty("lineItem", LineItem.class);
        Integer newQuantity = event.getProperty("newQuantity", Integer.class);
        if (selected == null || newQuantity == null) {
            view.closeDialog();
            return;
        }
        // Unchanged-quantity contract: no service call, no event, no journal entry. Handled
        // here (as well as in the service) so the "unchanged produces no event" test doesn't
        // depend on the service call short-circuiting.
        if (!selected.isVoided() && selected.getQuantity() == newQuantity) {
            view.closeDialog();
            return;
        }
        try {
            parent.getTransactionService().updateLineItemQuantity(selected, newQuantity);
        } catch (RuntimeException ignored) {
            // Service already dispatched an ERROR event; close and let the cashier retry.
            view.closeDialog();
            return;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("lineItem", selected);
        props.put("newQuantity", newQuantity);
        parent.dispatchPosEvent(new PosEvent(PosEventType.QUANTITY_CHANGED, props));
        view.closeDialog();
    }
}
