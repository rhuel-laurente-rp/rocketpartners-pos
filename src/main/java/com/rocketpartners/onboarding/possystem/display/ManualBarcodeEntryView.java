package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.HashMap;
import java.util.Map;

/**
 * Modal on-screen numeric keypad for manual barcode entry — the touch fallback for when a barcode
 * won't scan and there is no physical keyboard on the lane. Opened from the scan bar's keypad
 * button ({@link PosEventType#MANUAL_ENTRY_PRESSED}); the hardware scanner stays the primary path,
 * so this dialog exists only to cover the occasional damaged or missing label.
 *
 * <p><strong>It re-uses the scan path, it does not invent a new one.</strong> Confirming dispatches
 * {@link PosEventType#SCAN_SUBMIT_PRESSED} carrying the keyed digits as {@code raw} — exactly what
 * the scan field dispatches on Enter. {@link ScannerViewController} then validates and either adds
 * the item or paints the same inline error into the scan bar. So an unknown UPC keyed here surfaces
 * as {@code Item Not Found — …} in the bar, identical to a scan, rather than duplicating the
 * message inside this dialog.</p>
 *
 * <p><strong>Digits only.</strong> A {@link DocumentFilter} on the entry field rejects any
 * non-digit and caps the length, and the keypad ({@link OnScreenKeypad} without a decimal key)
 * types through that same document, so a tapped key can never smuggle in a character the field
 * would reject from a physical keyboard. Confirming an empty field is a no-op — nothing to submit —
 * so a stray Enter or a mis-tapped confirm can't dispatch a blank scan.</p>
 *
 * <p><strong>Shell.</strong> {@link PosDialog} chrome like every other modal: dark header, Cancel
 * on the left, the confirm primary on the right, ESC cancels, Enter confirms.</p>
 */
public class ManualBarcodeEntryView extends PosDialog {

    /** Field/keypad width — wide enough for the longest realistic barcode in a monospaced face. */
    private static final int BODY_WIDTH = 300;

    /**
     * Max characters the field accepts. Generous: the longest common symbologies are 14 digits
     * (ITF-14); this leaves headroom without letting an accidental key-mash overflow parsing.
     * Deliberately not a UPC-length check — {@code Barcodes.isValidUpc} accepts any digit run, and
     * the bundled pricebook carries UPCs of assorted lengths.
     */
    private static final int MAX_LENGTH = 18;

    private final IPosEventDispatcher dispatcher;

    private final JTextField entryField = new JTextField();
    private final JLabel hintLine = new JLabel(" ");
    private final PosButton confirmButton;
    private final PosButton cancelButton;
    private OnScreenKeypad keypad;

    /**
     * @param owner      the parent frame; may be {@code null}
     * @param dispatcher target for view-input events; must not be {@code null}
     */
    public ManualBarcodeEntryView(JFrame owner, IPosEventDispatcher dispatcher) {
        super(owner, "Enter Barcode");
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        this.dispatcher = dispatcher;

        this.confirmButton = PosButtons.primary("Add Item");
        this.cancelButton = PosButtons.secondary("Cancel");

        setBody(buildBody());

        confirmButton.addActionListener(e -> onConfirm());
        setPrimary(confirmButton);

        cancelButton.addActionListener(e -> closeDialog());
        addSecondary(cancelButton);
        // ESC and the Cancel button both just close — no tender or scan is dispatched on abandon.
        setCancelAction(this::closeDialog);
        setInitialFocus(entryField);
    }

    // ---- Public API called by ManualBarcodeEntryViewController -------------

    /**
     * Clears the field and opens the dialog fresh. Modal — blocks until the cashier confirms or
     * cancels, exactly like the cash-entry dialog.
     */
    public void prepareAndOpen() {
        entryField.setText("");
        hintLine.setText(" ");
        openDialog();
    }

    // ---- Handlers ---------------------------------------------------------

    private void onConfirm() {
        String raw = entryField.getText();
        raw = raw == null ? "" : raw.trim();
        if (raw.isEmpty()) {
            // Nothing keyed — do not dispatch a blank scan. Keep the dialog open with a nudge.
            hintLine.setText("Enter a barcode.");
            entryField.requestFocusInWindow();
            return;
        }
        // Re-use the manual-scan path: the scanner controller validates and either adds the item
        // or paints the inline error into the scan bar. Close first so the result (success flash or
        // Item-Not-Found hint) is visible on the bar behind us.
        closeDialog();
        Map<String, Object> props = new HashMap<>();
        props.put("raw", raw);
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.SCAN_SUBMIT_PRESSED, props));
    }

    // ---- Layout -----------------------------------------------------------

    private JPanel buildBody() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        JLabel eyebrow = new JLabel("Barcode");
        eyebrow.setFont(PosTheme.eyebrow());
        eyebrow.setForeground(PosTheme.MUTED);
        eyebrow.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(eyebrow);
        body.add(Box.createVerticalStrut(6));

        entryField.setName("manualBarcodeField");
        entryField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 18));
        entryField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PosTheme.RULE, 1),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        entryField.setPreferredSize(new Dimension(BODY_WIDTH, 52));
        entryField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        entryField.setAlignmentX(Component.LEFT_ALIGNMENT);
        entryField.putClientProperty("JTextField.placeholderText", "Type or tap the barcode digits");
        installDigitFilter(entryField);
        body.add(entryField);
        body.add(Box.createVerticalStrut(8));

        // Reserved hint row — swapped, never hidden — so the dialog doesn't resize when the empty
        // nudge appears.
        hintLine.setFont(PosTheme.base(Font.PLAIN, PosTheme.BODY));
        hintLine.setForeground(PosTheme.STOP);
        hintLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(hintLine);
        body.add(Box.createVerticalStrut(12));

        // Numeric keypad, no decimal — a barcode is whole digits. Types through the field's
        // Document, so the DigitFilter governs a tapped key exactly as a physical keystroke.
        keypad = new OnScreenKeypad(entryField, false);
        keypad.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(keypad);

        return body;
    }

    private void installDigitFilter(JTextField field) {
        if (field.getDocument() instanceof AbstractDocument doc) {
            doc.setDocumentFilter(new DigitFilter(MAX_LENGTH));
        }
    }

    // ---- Test hooks -------------------------------------------------------

    JTextField getEntryFieldForTest() { return entryField; }

    OnScreenKeypad getKeypadForTest() { return keypad; }

    PosButton getConfirmButtonForTest() { return confirmButton; }

    PosButton getCancelButtonForTest() { return cancelButton; }

    JLabel getHintLineForTest() { return hintLine; }

    /**
     * Accepts digits only, capped at {@code maxLength} characters total. Rejects — silently —
     * letters, symbols, and any paste that would exceed the cap or contain a non-digit.
     */
    private static final class DigitFilter extends DocumentFilter {
        private final int maxLength;

        DigitFilter(int maxLength) {
            this.maxLength = maxLength;
        }

        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                throws BadLocationException {
            String current = fb.getDocument().getText(0, fb.getDocument().getLength());
            String candidate = current.substring(0, offset) + string + current.substring(offset);
            if (isValid(candidate)) super.insertString(fb, offset, string, attr);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            String current = fb.getDocument().getText(0, fb.getDocument().getLength());
            String candidate = current.substring(0, offset) + text + current.substring(offset + length);
            if (isValid(candidate)) super.replace(fb, offset, length, text, attrs);
        }

        private boolean isValid(String s) {
            if (s.length() > maxLength) return false;
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) return false;
            }
            return true;
        }
    }
}
