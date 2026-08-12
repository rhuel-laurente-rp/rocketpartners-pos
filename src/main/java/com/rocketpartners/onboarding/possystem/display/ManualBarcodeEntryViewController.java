package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.component.IController;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Opens the {@link ManualBarcodeEntryView} keypad dialog when the cashier taps the scan bar's
 * keypad button ({@link PosEventType#MANUAL_ENTRY_PRESSED}).
 *
 * <p>Deliberately thin. The dialog re-uses the scan path — its confirm dispatches
 * {@link PosEventType#SCAN_SUBMIT_PRESSED}, handled by {@link ScannerViewController} — so this
 * controller owns nothing but the open/close lifecycle. One controller per dialog, matching
 * {@link PayWithCashViewController} and the other dialog controllers.</p>
 */
public class ManualBarcodeEntryViewController implements IController, IPosEventListener {

    private static final Set<PosEventType> LISTEN_TYPES =
            Collections.unmodifiableSet(EnumSet.of(PosEventType.MANUAL_ENTRY_PRESSED));

    private final ManualBarcodeEntryView view;
    private PosComponent parent;

    /**
     * @param view the keypad-entry modal; must not be {@code null}
     */
    public ManualBarcodeEntryViewController(ManualBarcodeEntryView view) {
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
        if (event.getType() == PosEventType.MANUAL_ENTRY_PRESSED) {
            view.prepareAndOpen();
        }
    }
}
