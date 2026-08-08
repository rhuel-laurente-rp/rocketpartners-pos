package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * The scan bar mounted at the top of the Basket column.
 *
 * <p>Layout: a {@code Scan} eyebrow, an editable field for manual UPC entry, and a small
 * status hint to the right ({@link #STATUS_READY} / {@link #STATUS_SCANNING} /
 * {@link #STATUS_LOCKED}). Everything sits on one row so cause (a barcode arriving) and effect
 * (a line item appearing directly beneath) are visually adjacent.</p>
 *
 * <p>Restyled to fit the POS design system: the field has a {@link PosTheme#RULE} border that
 * turns {@link PosTheme#GO} on focus, {@link PosTheme#MUTED} placeholder text, and the status
 * hint is right-aligned in {@link PosTheme#EYEBROW} style. Reads as part of the basket panel
 * it sits inside, not a widget dropped on top.</p>
 */
public class ScannerView extends JPanel {

    /** "Ready to scan" — the idle default. */
    public static final String STATUS_READY = "Ready to scan";
    /** Shown briefly while a scanner burst is being received. */
    public static final String STATUS_SCANNING = "Scanning…";
    /** Shown when scans are suspended (transaction TOTALED or a modal dialog is open). */
    public static final String STATUS_LOCKED = "Locked — press Total to tender";

    private static final String PLACEHOLDER = "Scan or type a UPC and press Enter";

    private final IPosEventDispatcher dispatcher;

    private final JTextField scanField = new JTextField();
    private final JLabel statusHint = new JLabel(STATUS_READY);
    private final JLabel eyebrow = new JLabel("SCAN");

    private final Border idleBorder;
    private final Border focusBorder;

    /**
     * @param dispatcher target for view-input events; must not be {@code null}
     */
    public ScannerView(IPosEventDispatcher dispatcher) {
        super(new BorderLayout(12, 0));
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        this.dispatcher = dispatcher;
        setOpaque(false);

        eyebrow.setFont(PosTheme.eyebrow());
        eyebrow.setForeground(PosTheme.MUTED);
        eyebrow.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));

        idleBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PosTheme.RULE, 1),
                BorderFactory.createEmptyBorder(7, 12, 7, 12));
        focusBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PosTheme.GO, 2),
                BorderFactory.createEmptyBorder(6, 11, 6, 11));

        scanField.setName("scanField");
        scanField.setFont(PosTheme.base(Font.PLAIN, PosTheme.ROW));
        scanField.setPreferredSize(new Dimension(240, 40));
        scanField.setBorder(idleBorder);
        installPlaceholder(scanField, PLACEHOLDER);
        scanField.addActionListener(e -> submitCurrentField());
        scanField.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                scanField.setBorder(focusBorder);
            }
            @Override public void focusLost(FocusEvent e) {
                scanField.setBorder(idleBorder);
            }
        });

        statusHint.setFont(PosTheme.eyebrow());
        statusHint.setForeground(PosTheme.MUTED);
        statusHint.setHorizontalAlignment(SwingConstants.RIGHT);

        add(eyebrow, BorderLayout.WEST);
        add(scanField, BorderLayout.CENTER);
        add(statusHint, BorderLayout.EAST);
    }

    // ---- Public API called by ScannerViewController -----------------------

    public JTextField getScanField() {
        return scanField;
    }

    public String getScanText() {
        String text = scanField.getText();
        // If the placeholder is showing, treat the field as empty.
        return isPlaceholderShowing(scanField) || text == null ? "" : text;
    }

    public void setScanText(String text) {
        clearPlaceholderState(scanField);
        scanField.setText(text == null ? "" : text);
    }

    public void clearScanField() {
        clearPlaceholderState(scanField);
        scanField.setText("");
        showPlaceholderIfEmpty(scanField);
    }

    public void requestScanFieldFocus() {
        scanField.requestFocusInWindow();
    }

    public void setStatusHint(String message) {
        statusHint.setText(message == null ? " " : message);
        System.out.println(message);
        java.awt.Color colour = PosTheme.MUTED;
        if (message != null) {
            if (message.equals(STATUS_LOCKED)) colour = PosTheme.STOP;
            else if (message.equals(STATUS_SCANNING)) colour = PosTheme.GO;
        }
        statusHint.setForeground(colour);
    }

    // ---- Internals --------------------------------------------------------

    private void submitCurrentField() {
        Map<String, Object> props = new HashMap<>();
        props.put("raw", getScanText());
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.SCAN_SUBMIT_PRESSED, props));
    }

    // ---- Placeholder plumbing ---------------------------------------------
    // Swing has no native placeholder for JTextField; wire it via focus listeners plus a
    // client property so getScanText() can distinguish "empty" from "user typed the placeholder
    // string literally".

    private static final String PLACEHOLDER_ACTIVE = "scanner.placeholder.active";

    private void installPlaceholder(JTextField field, String text) {
        field.putClientProperty(PLACEHOLDER_ACTIVE, Boolean.TRUE);
        field.setText(text);
        field.setForeground(PosTheme.MUTED);
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                if (Boolean.TRUE.equals(field.getClientProperty(PLACEHOLDER_ACTIVE))) {
                    field.setText("");
                    field.setForeground(PosTheme.INK);
                    field.putClientProperty(PLACEHOLDER_ACTIVE, Boolean.FALSE);
                }
            }
            @Override public void focusLost(FocusEvent e) {
                showPlaceholderIfEmpty(field);
            }
        });
    }

    private static boolean isPlaceholderShowing(JTextComponent field) {
        return Boolean.TRUE.equals(field.getClientProperty(PLACEHOLDER_ACTIVE));
    }

    private static void clearPlaceholderState(JTextComponent field) {
        if (isPlaceholderShowing(field)) {
            field.setText("");
        }
        field.setForeground(PosTheme.INK);
        field.putClientProperty(PLACEHOLDER_ACTIVE, Boolean.FALSE);
    }

    private static void showPlaceholderIfEmpty(JTextComponent field) {
        String txt = field.getText();
        if (txt == null || txt.isEmpty()) {
            field.putClientProperty(PLACEHOLDER_ACTIVE, Boolean.TRUE);
            field.setText(PLACEHOLDER);
            field.setForeground(PosTheme.MUTED);
        }
    }
}
