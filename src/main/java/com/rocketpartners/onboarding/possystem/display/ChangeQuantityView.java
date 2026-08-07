package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * Modal change-quantity dialog: {@link PosDialog}-shelled, compact.
 *
 * <p><strong>Quantity is always ≥ 1.</strong> The domain call
 * {@link com.rocketpartners.onboarding.commons.model.Transaction#updateLineItemQuantity(LineItem, int)}
 * throws on values below 1, and this dialog makes that unreachable rather than trying to
 * translate a zero into a void. Void Line is the single source of truth for removing a line;
 * two paths to the same terminal state would mean two sets of bugs.</p>
 *
 * <p><strong>Why the editor uses {@link JFormattedTextField#PERSIST}.</strong> This is the
 * load-bearing line of the whole class. {@code JSpinner.NumberEditor}'s text field defaults to
 * {@link JFormattedTextField#COMMIT_OR_REVERT}: when it loses focus it asks its formatter to
 * parse the text and, on failure, <em>silently restores the last valid value</em>. Clicking
 * Confirm moves focus off the field, so with the default behaviour the cashier's invalid entry
 * is reverted to the original quantity before {@link #onConfirm()} ever runs. The handler then
 * reads a perfectly valid number, matches it against the current quantity, takes the no-op
 * branch, and closes the dialog — the cashier types {@code 0}, clicks Confirm, and nothing at
 * all happens with no explanation. {@code PERSIST} tells the field to leave the text alone on
 * focus loss so this class can validate what was actually typed and explain the problem.</p>
 *
 * <p><strong>Input hardening.</strong> Four layers:</p>
 * <ul>
 *   <li>{@link SpinnerNumberModel} with lower bound {@link #MIN_QUANTITY}: the model itself
 *       refuses out-of-range values.</li>
 *   <li>A {@link DocumentFilter} on the spinner's editor field rejects any non-digit character
 *       on both keystroke <em>and</em> paste — no letters, no {@code +}/{@code -}, no decimal
 *       point — and caps length at the digit count of the maximum, so no entry can overflow
 *       {@code int}.</li>
 *   <li>A wrapping {@link javax.swing.TransferHandler}, because
 *       {@code JFormattedTextField} routes paste through its formatter and
 *       {@code setValue}, bypassing the document filter entirely.</li>
 *   <li>{@link JSpinner#commitEdit()} once the typed text has been validated, so the spinner's
 *       stored value matches what the cashier sees.</li>
 * </ul>
 *
 * <p>An invalid entry produces an inline {@link PosTheme#STOP} message beneath the field and
 * leaves the dialog open with the text selected for retyping. In-dialog validation belongs in
 * the dialog, not the error popup.</p>
 *
 * <p>{@code TransactionService} keeps validating independently. This dialog making bad input
 * unreachable is a convenience for the cashier, not the guarantee the domain relies on.</p>
 *
 * <p>Copy is sentence case throughout — title, body, field label, validation message, and both
 * button labels. Uppercase eyebrow rules on {@code PosTheme.EYEBROW} labels come from the
 * theme's font and tracking, not from capitalising the string.</p>
 */
public class ChangeQuantityView extends PosDialog {

    static final int MIN_QUANTITY = 1;

    /** Forces a minimum body width so validation messages lay out without re-packing. */
    private static final int BODY_MIN_WIDTH = 340;

    private final IPosEventDispatcher dispatcher;
    private final int maxQuantity;

    private final JLabel descriptionLabel = new JLabel(" ");
    private final JSpinner quantitySpinner;
    private final JLabel validationMessage = new JLabel(" ");
    private final PosButton confirmButton;
    private final PosButton cancelButton;

    /**
     * Single shared filter instance; reused across document swaps by
     * {@link #attachFilterToCurrentDocument()}.
     */
    private final DigitOnlyFilter digitFilter;

    private LineItem lineItem;

    /**
     * @param owner       the parent frame; may be {@code null}
     * @param dispatcher  target for view-input events; must not be {@code null}
     * @param maxQuantity upper bound wired to the spinner model
     */
    public ChangeQuantityView(JFrame owner, IPosEventDispatcher dispatcher, int maxQuantity) {
        super(owner, "Change quantity");
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        if (maxQuantity < MIN_QUANTITY) {
            throw new IllegalArgumentException(
                    "maxQuantity must be >= " + MIN_QUANTITY + ", got " + maxQuantity);
        }
        this.dispatcher = dispatcher;
        this.maxQuantity = maxQuantity;
        this.digitFilter = new DigitOnlyFilter(String.valueOf(maxQuantity).length());

        this.quantitySpinner = new JSpinner(
                new SpinnerNumberModel(MIN_QUANTITY, MIN_QUANTITY, maxQuantity, 1));
        this.confirmButton = PosButtons.primary("Confirm change");
        this.cancelButton = PosButtons.danger("Cancel");

        setBody(buildBody());

        confirmButton.addActionListener(e -> onConfirm());
        setPrimary(confirmButton);

        cancelButton.addActionListener(e -> fireCancel());
        addSecondary(cancelButton);
        setCancelAction(this::fireCancel);
        setInitialFocus(quantitySpinner);

        configureEditor();
        matchFooterButtonSizes();
    }

    // ---- Public API called by ChangeQuantityViewController ----------------

    public void openFor(LineItem lineItem) {
        if (lineItem == null) throw new IllegalArgumentException("lineItem must not be null");
        this.lineItem = lineItem;
        descriptionLabel.setText(lineItem.getItem().getDescription());
        quantitySpinner.setValue(Math.max(MIN_QUANTITY, lineItem.getQuantity()));
        clearValidationMessage();
        openDialog();
        // Select the existing quantity so the first keystroke replaces it rather than
        // appending to it — a cashier changing 1 to 5 shouldn't end up with 15.
        editorField().selectAll();
    }

    // ---- Handlers ---------------------------------------------------------

    private void onConfirm() {
        // Safe only because the editor is in PERSIST mode (see class Javadoc). Under the
        // default COMMIT_OR_REVERT this reads the reverted original value, not what was typed.
        String raw = editorField().getText();
        raw = raw == null ? "" : raw.trim();

        if (raw.isEmpty()) {
            rejectWith("Enter a quantity between " + MIN_QUANTITY + " and " + maxQuantity + ".");
            return;
        }

        int typed;
        try {
            typed = Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            // The digit filter's length cap should make overflow unreachable; if a future
            // refactor lets a longer or non-numeric string land here, prompt rather than throw.
            rejectWith("Enter a quantity between " + MIN_QUANTITY + " and " + maxQuantity + ".");
            return;
        }

        if (typed < MIN_QUANTITY || typed > maxQuantity) {
            rejectWith("Enter a quantity between " + MIN_QUANTITY + " and " + maxQuantity + ".");
            return;
        }

        // In range — sync the spinner's stored value to the text before proceeding.
        try {
            quantitySpinner.commitEdit();
        } catch (ParseException ex) {
            rejectWith("Enter a quantity between " + MIN_QUANTITY + " and " + maxQuantity + ".");
            return;
        }

        // Unchanged is a no-op: no service call, no journal entry, no dispatched event. Both
        // the dialog and controller enforce this rule so neither depends on the other.
        if (lineItem != null && !lineItem.isVoided() && lineItem.getQuantity() == typed) {
            closeDialog();
            return;
        }

        Map<String, Object> props = new HashMap<>();
        props.put("lineItem", lineItem);
        props.put("newQuantity", typed);
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CHANGE_QTY_CONFIRM_PRESSED, props));
    }

    private void fireCancel() {
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CHANGE_QTY_CANCEL_PRESSED));
    }

    /** Shows the message, returns focus to the field, and selects the text for retyping. */
    private void rejectWith(String message) {
        validationMessage.setText(message);
        quantitySpinner.requestFocusInWindow();
        editorField().selectAll();
    }

    private void clearValidationMessage() {
        // The label keeps its slot in the layout at all times — toggling visibility would make
        // the dialog change height and re-pack under the cashier's cursor mid-correction.
        validationMessage.setText(" ");
    }

    // ---- Test hooks -------------------------------------------------------

    /** For tests: the spinner backing this dialog. */
    JSpinner getSpinnerForTest() {
        return quantitySpinner;
    }

    /** For tests: the spinner's underlying text-field editor. */
    JFormattedTextField getSpinnerEditorForTest() {
        return editorField();
    }

    /** For tests: the inline validation label. */
    JLabel getValidationMessageForTest() {
        return validationMessage;
    }

    /** For tests: the confirm button. */
    PosButton getConfirmButtonForTest() {
        return confirmButton;
    }

    /** For tests: the cancel button. */
    PosButton getCancelButtonForTest() {
        return cancelButton;
    }

    // ---- Internals --------------------------------------------------------

    private JFormattedTextField editorField() {
        return ((JSpinner.DefaultEditor) quantitySpinner.getEditor()).getTextField();
    }

    private JPanel buildBody() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        descriptionLabel.setFont(PosTheme.base(Font.BOLD, PosTheme.ROW));
        descriptionLabel.setForeground(PosTheme.INK);
        descriptionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(descriptionLabel);
        body.add(Box.createVerticalStrut(14));

        // Eyebrow labels are uppercased through the theme's eyebrow font/tracking pair, not by
        // capitalising the string. The label text stays sentence case for consistency with the
        // rest of the dialog's copy.
        JLabel eyebrow = new JLabel("Quantity");
        eyebrow.setFont(PosTheme.eyebrow());
        eyebrow.setForeground(PosTheme.MUTED);
        eyebrow.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(eyebrow);
        body.add(Box.createVerticalStrut(6));

        quantitySpinner.setFont(PosTheme.base(Font.BOLD, PosTheme.HEADLINE));
        JComponent editor = quantitySpinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor de) {
            de.getTextField().setFont(PosTheme.base(Font.BOLD, PosTheme.HEADLINE));
            de.getTextField().setColumns(4);
        }
        quantitySpinner.setPreferredSize(new Dimension(140, 56));
        quantitySpinner.setMaximumSize(new Dimension(180, 56));
        quantitySpinner.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(quantitySpinner);

        validationMessage.setFont(PosTheme.base(Font.PLAIN, PosTheme.BODY));
        validationMessage.setForeground(PosTheme.STOP);
        validationMessage.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(Box.createVerticalStrut(8));
        body.add(validationMessage);
        body.add(validationMessage);

        return body;
    }


    private void configureEditor() {
        JFormattedTextField field = editorField();

        // The fix for the reverted-input bug. Without PERSIST, focus moving to the Confirm
        // button makes the field discard invalid text and restore the previous value, so
        // onConfirm can never see — or report on — what the cashier actually typed.
        field.setFocusLostBehavior(JFormattedTextField.PERSIST);

        // Two paste/typing layers, because JFormattedTextField goes out of its way to defeat
        // one on its own:
        //
        //   1. DocumentFilter — catches keystrokes and any paste that goes through the
        //      Document.replace path. The field can swap its Document during commitEdit and
        //      setValue plumbing, so the "document" property listener re-attaches the filter
        //      whenever that happens.
        //   2. TransferHandler — catches paste specifically. JFormattedTextField's own
        //      TransferHandler parses the clipboard content via its formatter and calls
        //      setValue directly, bypassing the Document (and therefore the DocumentFilter)
        //      entirely.
        attachFilterToCurrentDocument();
        field.addPropertyChangeListener("document", e -> attachFilterToCurrentDocument());

        javax.swing.TransferHandler original = field.getTransferHandler();
        field.setTransferHandler(
                new DigitOnlyTransferHandler(original, String.valueOf(maxQuantity).length()));
    }

    private void attachFilterToCurrentDocument() {
        if (editorField().getDocument() instanceof AbstractDocument doc) {
            doc.setDocumentFilter(digitFilter);
        }
    }

    private void matchFooterButtonSizes() {
        // Confirm and Cancel report identical widths and heights. PosDialog#setPrimary sizes
        // the primary to BUTTON_HEIGHT_PRIMARY + SHADOW_INSET; we mirror the same on Cancel
        // and take the wider of the two natural widths so neither is cut off.
        Dimension confirmPref = confirmButton.getPreferredSize();
        Dimension cancelPref = cancelButton.getPreferredSize();
        Dimension shared = new Dimension(
                Math.max(confirmPref.width, cancelPref.width),
                Math.max(confirmPref.height, cancelPref.height));
        confirmButton.setPreferredSize(shared);
        cancelButton.setPreferredSize(shared);
    }

    /**
     * Wraps the spinner editor's stock {@link javax.swing.TransferHandler} and rejects
     * clipboard content that isn't all-digits or that exceeds the maximum's digit count.
     * Necessary because {@code JFormattedTextField}'s transfer handler routes paste through
     * {@code setValue(parsed)}, bypassing the {@link DocumentFilter} chain entirely — a filter
     * alone is not enough to keep {@code -3} out of the field.
     */
    private static final class DigitOnlyTransferHandler extends javax.swing.TransferHandler {
        private final javax.swing.TransferHandler delegate;
        private final int maxLength;

        DigitOnlyTransferHandler(javax.swing.TransferHandler delegate, int maxLength) {
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
                if (pasted != null
                        && (!DigitOnlyFilter.isAllDigits(pasted) || pasted.length() > maxLength)) {
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
     * Accepts digits only, up to {@code maxLength} characters. Rejects — silently — letters,
     * symbols, signs, decimal points, and any pasted string containing a non-digit. A
     * partially-legal paste (e.g. {@code "12abc"}) is rejected wholesale rather than stripped,
     * because the cashier didn't type {@code "12"} and shouldn't see {@code "12"} appear.
     *
     * <p>The length cap keeps entries inside {@code int} range, so {@link Integer#parseInt}
     * in the confirm handler cannot overflow on an all-digit string.</p>
     */
    private static final class DigitOnlyFilter extends DocumentFilter {
        private final int maxLength;

        DigitOnlyFilter(int maxLength) {
            this.maxLength = maxLength;
        }

        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                throws BadLocationException {
            if (isAllDigits(string)
                    && fb.getDocument().getLength() + length(string) <= maxLength) {
                super.insertString(fb, offset, string, attr);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text,
                            AttributeSet attrs) throws BadLocationException {
            if (isAllDigits(text)
                    && fb.getDocument().getLength() - length + length(text) <= maxLength) {
                super.replace(fb, offset, length, text, attrs);
            }
        }

        private static int length(String s) {
            return s == null ? 0 : s.length();
        }

        static boolean isAllDigits(String s) {
            if (s == null || s.isEmpty()) return true;
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) return false;
            }
            return true;
        }
    }
}