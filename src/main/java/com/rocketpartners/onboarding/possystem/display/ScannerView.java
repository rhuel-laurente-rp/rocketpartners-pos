package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.HashMap;
import java.util.Map;

/**
 * The scan bar mounted at the top of the Basket column.
 *
 * <p>Layout: a {@code Scan} label, an editable field for manual UPC entry, and a small status
 * hint to the right ({@code Ready to scan} / {@code Scanning…} / {@code Locked — press Total
 * to tender}). Everything sits on one row so cause (a barcode arriving) and effect (a line
 * item appearing directly beneath) are visually adjacent.</p>
 *
 * <p>The view is deliberately dumb: it does not classify scanner bursts, does not validate
 * UPCs, and does not touch the transaction. The application-wide
 * {@code KeyEventDispatcher} the controller installs is what actually captures scanner input;
 * this view's own text field is the fallback path for slow manual typing. On Enter the field
 * dispatches a {@link PosEventType#SCAN_SUBMIT_PRESSED} event carrying the raw field text.</p>
 *
 * <p>Public API for the controller: {@link #getScanField()} (so the controller can request
 * focus after every user interaction), {@link #getScanText()}, {@link #setScanText(String)},
 * {@link #clearScanField()}, and {@link #setStatusHint(String)}.</p>
 */
public class ScannerView extends JPanel {

    /** "Ready to scan" — the idle default. */
    public static final String STATUS_READY = "Ready to scan";
    /** Shown briefly while a scanner burst is being received. */
    public static final String STATUS_SCANNING = "Scanning…";
    /** Shown when scans are suspended (transaction TOTALED or a modal dialog is open). */
    public static final String STATUS_LOCKED = "Locked — press Total to tender";

    private final IPosEventDispatcher dispatcher;

    private final JTextField scanField = new JTextField();
    private final JLabel statusHint = new JLabel(STATUS_READY);

    /**
     * @param dispatcher target for view-input events; must not be {@code null}
     */
    public ScannerView(IPosEventDispatcher dispatcher) {
        super(new BorderLayout(6, 0));
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        this.dispatcher = dispatcher;

        setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));

        JLabel scanLabel = new JLabel("Scan:");
        scanLabel.setFont(scanLabel.getFont().deriveFont(Font.BOLD));

        scanField.setColumns(20);
        scanField.setName("scanField");
        scanField.setPreferredSize(new Dimension(220, scanField.getPreferredSize().height));
        scanField.addActionListener(e -> submitCurrentField());

        statusHint.setFont(statusHint.getFont().deriveFont(Font.ITALIC));
        statusHint.setAlignmentX(Component.RIGHT_ALIGNMENT);

        add(scanLabel, BorderLayout.WEST);
        add(scanField, BorderLayout.CENTER);
        add(statusHint, BorderLayout.EAST);
    }

    // ---- Public API called by ScannerViewController -----------------------

    /** @return the {@link JTextField} the controller uses to request/return focus */
    public JTextField getScanField() {
        return scanField;
    }

    /** @return the current text in the scan field, never {@code null} */
    public String getScanText() {
        String text = scanField.getText();
        return text == null ? "" : text;
    }

    /** Replaces the field's contents. */
    public void setScanText(String text) {
        scanField.setText(text == null ? "" : text);
    }

    /** Clears the field. */
    public void clearScanField() {
        scanField.setText("");
    }

    /** Restores focus to the scan field. Called after every user interaction. */
    public void requestScanFieldFocus() {
        scanField.requestFocusInWindow();
    }

    /** Updates the small status hint to the right of the field. */
    public void setStatusHint(String message) {
        statusHint.setText(message == null ? " " : message);
    }

    private void submitCurrentField() {
        Map<String, Object> props = new HashMap<>();
        props.put("raw", getScanText());
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.SCAN_SUBMIT_PRESSED, props));
    }
}
