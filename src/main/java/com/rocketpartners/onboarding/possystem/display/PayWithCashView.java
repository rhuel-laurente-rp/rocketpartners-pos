package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultFormatter;
import javax.swing.text.DefaultFormatterFactory;
import javax.swing.text.DocumentFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Modal cash-entry-and-confirm dialog: {@link PosDialog}-shelled, register-shaped. This is the
 * <em>Other Amount</em> step of the cash flow — reached only when the cashier picks Other Amount
 * on {@link CashModeChoiceView} rather than the two terminal one-tap tiles. The cashier keys
 * what the customer handed over; change is computed against the true grand total.
 *
 * <p><strong>Back, not Cancel — and a separate way out.</strong> The footer secondary is
 * labelled <em>Back</em>: it dispatches {@link PosEventType#CASH_ENTRY_BACK_PRESSED}, returning
 * to the mode choice without tendering, so a cashier who meant Exact Amount need not re-open Pay
 * Cash. Because Back is a sub-step navigation rather than a full exit, ESC is wired separately to
 * {@link PosEventType#CASH_CANCEL_PRESSED} — the way to abandon the cash flow entirely from here.
 * Either way, no tender event is dispatched and the transaction stays re-tenderable.</p>
 *
 * <p><strong>Body layout, top to bottom:</strong></p>
 * <ol>
 *   <li>A one-line summary of the amount owed: {@code "Amount due: $17.70 (exact amount)"} or
 *       {@code "Amount due: $18.00 (pay next dollar)"}, depending on which mode the cashier
 *       picked upstream. Tax is already folded into the amount due — this is the
 *       transaction's grand total from {@link com.rocketpartners.onboarding.commons.model.Transaction#grandTotal()}.</li>
 *   <li>The cash-received input field, pre-filled and {@code selectAll()}'d so the first
 *       keystroke replaces it.</li>
 *   <li>A single status strip that carries either the live change amount
 *       ({@code "Change due: $2.30"}, GO-tinted) when the entry is valid and above the amount
 *       due, or an inline error ({@code "Amount is less than the amount due"}, STOP-tinted)
 *       when the entry is empty, malformed, negative, or below the amount due.</li>
 * </ol>
 *
 * <p><strong>Why the editor uses {@link JFormattedTextField#PERSIST}.</strong> The load-bearing
 * line of the whole class. {@code JFormattedTextField}'s default focus-lost behaviour is
 * {@link JFormattedTextField#COMMIT_OR_REVERT}: when it loses focus the formatter parses the
 * text and, on failure, <em>silently restores the last valid value</em>. Clicking Confirm
 * moves focus off the field, so under the default the cashier's invalid entry is reverted to
 * the last valid amount before {@link #onConfirm()} runs. The handler then reads a plausible
 * number and tenders it — a wrong payment with real change handed to a real customer. See
 * {@link ChangeQuantityView}'s class Javadoc for the full explanation of the same bug in a
 * quantity context; here the consequences are money.</p>
 *
 * <p><strong>Input hardening.</strong> Four layers, mirroring {@link ChangeQuantityView}:</p>
 * <ul>
 *   <li>A {@link DocumentFilter} on the underlying editor field rejects any character that
 *       isn't a digit or a decimal point, caps the entry at one decimal point and two decimal
 *       places, and enforces a total length ceiling so parsing cannot overflow.</li>
 *   <li>A wrapping {@link javax.swing.TransferHandler}, because {@link JFormattedTextField}
 *       routes paste through its formatter and {@code setValue}, bypassing the document filter
 *       entirely.</li>
 *   <li>Confirm-time raw validation: parse the actual typed text (which is possible because
 *       PERSIST kept it), then reject empty / non-numeric / negative / underpayment inputs
 *       with an inline message.</li>
 *   <li>{@code commitEdit()} once the typed text is known good, so the formatter's stored
 *       value matches what the cashier sees.</li>
 * </ul>
 *
 * <p><strong>One status strip, two tones.</strong> The strip always claims its slot in the
 * layout — its text is swapped, never its visibility — so the dialog doesn't resize under the
 * cashier's hand mid-correction. When the entry is valid and above the amount due the strip
 * announces the change due in GO (green). When the entry is invalid or below the amount due
 * the strip carries a STOP-red error message. When the field is empty the strip holds a
 * single space to preserve height.</p>
 */
public class PayWithCashView extends PosDialog {

    /** Forces a minimum body width so the status strip lays out without re-packing. */
    private static final int BODY_MIN_WIDTH = 360;

    /** Maximum length of the cash-received field: 8 chars fits {@code 99999.99}. */
    private static final int MAX_INPUT_LENGTH = 8;

    /**
     * The mode the cashier picked in step one. Only affects the label under Amount due — the
     * arithmetic (change vs grand total) is identical regardless.
     */
    public enum Mode {
        /** Cashier picked "Exact Amount" in step one. Amount due is the transaction's grand total. */
        EXACT("Exact Amount"),
        /** Cashier picked "Next Dollar" in step one. Amount due is grand total rounded up. */
        NEXT_DOLLAR("Pay Next Dollar");

        private final String label;
        Mode(String label) { this.label = label; }
        public String label() { return label; }
    }

    private final IPosEventDispatcher dispatcher;

    private final JLabel amountDueLine = new JLabel(" ");
    private final JFormattedTextField cashReceivedField;
    private final JLabel statusLine = new JLabel(" ");
    private final PosButton confirmButton;
    private final PosButton backButton;

    /** Single shared filter instance; reused across document swaps. */
    private final MoneyFilter moneyFilter = new MoneyFilter(MAX_INPUT_LENGTH);

    /**
     * The amount the customer must pay — the transaction's grand total (tax already included) including if pay next dollar or exact.
     * Never {@code null} once {@link #openFor} has been called.
     */
    private BigDecimal amountDue = BigDecimal.ZERO;

    /**
     * @param owner      the parent frame; may be {@code null}
     * @param dispatcher target for view-input events; must not be {@code null}
     */
    public PayWithCashView(JFrame owner, IPosEventDispatcher dispatcher) {
        super(owner, "Cash Payment");
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        this.dispatcher = dispatcher;

        this.cashReceivedField = buildCashField();
        this.confirmButton = PosButtons.primary("Confirm Payment");
        this.backButton = PosButtons.secondary("Back");

        setBody(buildBody());

        confirmButton.addActionListener(e -> onConfirm());
        setPrimary(confirmButton);

        // Back returns to the mode choice (a sub-step navigation, no tender). ESC is wired
        // separately to full abandon (CASH_CANCEL_PRESSED) so the cashier still has a way out of
        // the whole flow from here — see the class Javadoc.
        backButton.addActionListener(e -> fireBack());
        addSecondary(backButton);
        setCancelAction(this::fireCancel);
        setInitialFocus(cashReceivedField);

        configureEditor();
        matchFooterButtonSizes();
    }

    // ---- Public API called by PayWithCashViewController --------------------

    /**
     * Populates the dialog for a fresh open.
     *
     * @param amountDue the amount the customer must pay — the transaction's grand
     *                            total (tax included), already inflected for the picked mode:
     *                            equal to the exact grand total for {@link Mode#EXACT}, or
     *                            that total rounded up to the next whole dollar for
     *                            {@link Mode#NEXT_DOLLAR}. This <em>is</em> the pre-fill; both
     *                            validation and change-due arithmetic use it as the reference.
     *                            Must not be {@code null}.
     * @param mode                which mode the cashier picked in step one — decides the
     *                            parenthetical qualifier next to the amount due; must not be
     *                            {@code null}
     */
    public void openFor(BigDecimal amountDue, Mode mode) {
        if (amountDue == null) throw new IllegalArgumentException("amountDue must not be null");
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
        this.amountDue = amountDue.setScale(2, RoundingMode.HALF_UP);
        setHeaderAmount(this.amountDue);
        amountDueLine.setText("Amount Due: " + PosTheme.money(this.amountDue)
                + "  (" + mode.label() + ")");
        // The grand-total-amount-due IS the prefill. One source of truth for both the field
        // value and the validation reference — no risk of them drifting apart.
        cashReceivedField.setText("0");
        recomputeStatus();
        openDialog();
        // Select all AFTER openDialog: the initial-focus callback fires during window
        // activation and would clobber an earlier selection.
        cashReceivedField.selectAll();
    }

    /** The raw text currently in the cash-received field. */
    public String getCashReceivedText() {
        String text = cashReceivedField.getText();
        return text == null ? "" : text;
    }

    /**
     * Surface an error from the controller. The status strip stays visible; its tone flips to
     * STOP-red and the text is replaced. Does not close the dialog.
     */
    public void showError(String message) {
        showStatus(message == null || message.isEmpty() ? " " : message, PosTheme.STOP);
    }

    /** Clears any status text back to the initial blank slot. */
    public void clearStatus() {
        showStatus(" ", PosTheme.MUTED);
    }

    // ---- Handlers ---------------------------------------------------------

    private void onConfirm() {
        String raw = cashReceivedField.getText();
        raw = raw == null ? "" : raw.trim();

        String validation = validate(raw);
        if (validation != null) {
            rejectWith(validation);
            return;
        }

        try {
            cashReceivedField.commitEdit();
        } catch (java.text.ParseException ex) {
            rejectWith("Enter a valid dollar amount.");
            return;
        }

        Map<String, Object> props = new HashMap<>();
        props.put("cashReceived", raw);
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CASH_CONFIRM_PRESSED, props));
    }

    /** Back: return to the mode choice without tendering. */
    private void fireBack() {
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CASH_ENTRY_BACK_PRESSED));
    }

    /** ESC: abandon the cash flow entirely. */
    private void fireCancel() {
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CASH_CANCEL_PRESSED));
    }

    /** Shows an error, returns focus to the field, selects the text for retyping. */
    private void rejectWith(String message) {
        showError(message);
        cashReceivedField.requestFocusInWindow();
        cashReceivedField.selectAll();
    }

    /**
     * Reads the current field, and pushes either a change-due message (GO green) or an error
     * (STOP red) into the status strip. Called on every document change and once at open time.
     */
    private void recomputeStatus() {
        String raw = cashReceivedField.getText();
        raw = raw == null ? "" : raw.trim();
        if (raw.isEmpty()) {
            showStatus(" ", PosTheme.MUTED);
            return;
        }
        BigDecimal entered;
        try {
            entered = new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            showStatus("Enter a valid dollar amount.", PosTheme.STOP);
            return;
        }
        if (entered.signum() < 0) {
            showStatus("Amount must be non-negative.", PosTheme.STOP);
            return;
        }
        if (entered.scale() > 2) {
            showStatus("Amount cannot have more than two decimal places.", PosTheme.STOP);
            return;
        }
        if (entered.compareTo(amountDue) < 0) {
            showStatus("Amount is less than the amount due.", PosTheme.STOP);
            return;
        }
        BigDecimal change = entered.subtract(amountDue).setScale(2, RoundingMode.HALF_UP);
        showStatus("Change Due: " + PosTheme.money(change), PosTheme.GO);
    }

    /**
     * Same validation as {@link #recomputeStatus} but returning a message string (or {@code null}
     * for "valid"). Kept separate so {@link #onConfirm} can decide whether to dispatch without
     * re-parsing the strip's rendered text.
     */
    private String validate(String raw) {
        if (raw.isEmpty()) return "Enter a cash amount.";
        BigDecimal entered;
        try {
            entered = new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            return "Enter a valid dollar amount.";
        }
        if (entered.signum() < 0) return "Amount must be non-negative.";
        if (entered.scale() > 2) return "Amount cannot have more than two decimal places.";
        if (entered.compareTo(amountDue) < 0) return "Amount is less than the amount due.";
        return null;
    }

    private void showStatus(String text, java.awt.Color colour) {
        statusLine.setText(text);
        statusLine.setForeground(colour);
    }

    // ---- Test hooks -------------------------------------------------------

    JFormattedTextField getCashFieldForTest() {
        return cashReceivedField;
    }

    JLabel getStatusLineForTest() {
        return statusLine;
    }

    /**
     * Back-compat alias kept because earlier scaffolding tests referenced the old
     * "validationMessage" name. Points at the same combined status strip.
     */
    JLabel getValidationMessageForTest() {
        return statusLine;
    }

    JLabel getGrandTotalAmounDueLineForTest() {
        return amountDueLine;
    }

    PosButton getConfirmButtonForTest() {
        return confirmButton;
    }

    PosButton getBackButtonForTest() {
        return backButton;
    }

    // ---- Layout -----------------------------------------------------------

    private JFormattedTextField buildCashField() {
        // Plain DefaultFormatter with the field's own DocumentFilter enforcing shape. We do not
        // use NumberFormatter here — its parse-on-focus-lost behaviour is the exact bug PERSIST
        // exists to avoid, and we want to work off the raw string in onConfirm.
        DefaultFormatter fmt = new DefaultFormatter() {
            @Override
            public Object stringToValue(String text) {
                return text == null ? "" : text;
            }
            @Override
            public String valueToString(Object value) {
                return value == null ? "" : value.toString();
            }
        };
        fmt.setOverwriteMode(false);
        fmt.setCommitsOnValidEdit(false);
        JFormattedTextField field = new JFormattedTextField(new DefaultFormatterFactory(fmt));
        field.setFont(PosTheme.base(Font.BOLD, PosTheme.HEADLINE));
        field.setHorizontalAlignment(SwingConstants.RIGHT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PosTheme.RULE, 1),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));
        field.setPreferredSize(new Dimension(280, 56));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        return field;
    }

    private JPanel buildBody() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        // Force a minimum width so the status strip lays out without re-packing on message swap.

        amountDueLine.setFont(PosTheme.base(Font.PLAIN, PosTheme.ROW));
        amountDueLine.setForeground(PosTheme.INK);
        amountDueLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(amountDueLine);
        body.add(Box.createVerticalStrut(14));

        // Eyebrow above the cash-received field — matches the pattern used elsewhere so this
        // dialog reads visually consistent with ChangeQuantityView etc.
        JLabel cashEyebrow = new JLabel("Cash Received");
        cashEyebrow.setFont(PosTheme.eyebrow());
        cashEyebrow.setForeground(PosTheme.MUTED);
        cashEyebrow.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(cashEyebrow);
        body.add(Box.createVerticalStrut(6));

        cashReceivedField.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(cashReceivedField);
        body.add(Box.createVerticalStrut(10));

        // One status strip covering both change-due and validation. Always claims its slot —
        // text is swapped, never visibility — so the dialog doesn't resize on message swap.
        statusLine.setFont(PosTheme.base(Font.BOLD, PosTheme.BODY));
        statusLine.setForeground(PosTheme.MUTED);
        statusLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(statusLine);

        return body;
    }

    private void configureEditor() {
        cashReceivedField.setFocusLostBehavior(JFormattedTextField.PERSIST);

        attachFilterToCurrentDocument();
        cashReceivedField.addPropertyChangeListener("document", e -> attachFilterToCurrentDocument());

        javax.swing.TransferHandler original = cashReceivedField.getTransferHandler();
        cashReceivedField.setTransferHandler(new MoneyTransferHandler(original, MAX_INPUT_LENGTH));

        cashReceivedField.getDocument().addDocumentListener(new StatusListener());
        cashReceivedField.addPropertyChangeListener("document", e -> {
            javax.swing.text.Document doc = cashReceivedField.getDocument();
            if (doc != null) doc.addDocumentListener(new StatusListener());
        });
    }

    private void attachFilterToCurrentDocument() {
        if (cashReceivedField.getDocument() instanceof AbstractDocument doc) {
            doc.setDocumentFilter(moneyFilter);
        }
    }

    private void matchFooterButtonSizes() {
        Dimension confirmPref = confirmButton.getPreferredSize();
        Dimension backPref = backButton.getPreferredSize();
        Dimension shared = new Dimension(
                Math.max(confirmPref.width, backPref.width),
                Math.max(confirmPref.height, backPref.height));
        confirmButton.setPreferredSize(shared);
        backButton.setPreferredSize(shared);
    }

    /** Refreshes the status strip on every document mutation. */
    private final class StatusListener implements DocumentListener {
        @Override public void insertUpdate(DocumentEvent e) { recomputeStatus(); }
        @Override public void removeUpdate(DocumentEvent e) { recomputeStatus(); }
        @Override public void changedUpdate(DocumentEvent e) { recomputeStatus(); }
    }

    /**
     * Wraps the field's stock transfer handler and rejects clipboard content that isn't a
     * valid partial money string. Necessary because {@link JFormattedTextField}'s handler
     * routes paste through {@code setValue}, bypassing the {@link DocumentFilter}.
     */
    private static final class MoneyTransferHandler extends javax.swing.TransferHandler {
        private final javax.swing.TransferHandler delegate;
        private final int maxLength;

        MoneyTransferHandler(javax.swing.TransferHandler delegate, int maxLength) {
            this.delegate = delegate;
            this.maxLength = maxLength;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return delegate != null && delegate.canImport(support);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (delegate == null) return false;
            try {
                String pasted = (String) support.getTransferable()
                        .getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
                if (pasted != null && !MoneyFilter.isValidMoney(pasted, maxLength)) {
                    return false;
                }
            } catch (Exception ignored) {
                // Non-string flavour or unavailable — let the delegate decide.
            }
            return delegate.importData(support);
        }

        @Override
        public int getSourceActions(javax.swing.JComponent c) {
            return delegate == null ? NONE : delegate.getSourceActions(c);
        }
    }

    /**
     * Accepts digits and at most one decimal point, with at most two digits past it, capped at
     * {@code maxLength} characters total. Rejects — silently — letters, symbols, signs, second
     * decimal points, and any pasted string that fails the same rules on the resulting text.
     */
    private static final class MoneyFilter extends DocumentFilter {
        private final int maxLength;

        MoneyFilter(int maxLength) {
            this.maxLength = maxLength;
        }

        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                throws BadLocationException {
            String current = fb.getDocument().getText(0, fb.getDocument().getLength());
            String candidate = current.substring(0, offset) + string + current.substring(offset);
            if (isValidMoney(candidate, maxLength)) {
                super.insertString(fb, offset, string, attr);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text,
                            AttributeSet attrs) throws BadLocationException {
            String current = fb.getDocument().getText(0, fb.getDocument().getLength());
            String candidate = current.substring(0, offset) + text + current.substring(offset + length);
            if (isValidMoney(candidate, maxLength)) {
                super.replace(fb, offset, length, text, attrs);
            }
        }

        static boolean isValidMoney(String s, int maxLength) {
            if (s == null) return false;
            if (s.isEmpty()) return true;
            if (s.length() > maxLength) return false;
            int dotIndex = -1;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '.') {
                    if (dotIndex >= 0) return false;
                    dotIndex = i;
                } else if (!Character.isDigit(c)) {
                    return false;
                }
            }
            if (dotIndex >= 0 && s.length() - dotIndex - 1 > 2) return false;
            return true;
        }
    }
}
