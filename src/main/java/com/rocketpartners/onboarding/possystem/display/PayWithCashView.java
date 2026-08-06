package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Modal cash-entry dialog: a dumb Swing renderer that forwards every button press as a
 * {@link PosEvent}. The controller decides everything.
 *
 * <p>Layout: an {@code Amount Due} label showing the total payable, an editable
 * {@code Cash Received} field, two buttons that adjust the amount due ({@code Exact Amount},
 * {@code Next Dollar}), and {@code Confirm} / {@code Cancel} at the bottom. The buttons only
 * change the total payable displayed on the dialog — the sole commit path is Confirm.
 * Change is then computed as {@code cashReceived − amountDue}.</p>
 *
 * <p>Outbound: on click, dispatches a matching {@link PosEventType}
 * ({@link PosEventType#CASH_EXACT_PRESSED}, {@link PosEventType#CASH_NEXT_DOLLAR_PRESSED},
 * {@link PosEventType#CASH_CONFIRM_PRESSED} carrying a {@code cashReceived} string,
 * {@link PosEventType#CASH_CANCEL_PRESSED}).</p>
 *
 * <p>Inbound: small public API — {@link #setAmountDue(BigDecimal)},
 * {@link #setCashReceivedText(String)}, {@link #getCashReceivedText()},
 * {@link #showChangeDue(BigDecimal)}, {@link #showError(String)}, {@link #openDialog()},
 * {@link #closeDialog()} — that the controller calls to keep the dialog in sync.</p>
 */
public class PayWithCashView {

    private final JDialog dialog;
    private final IPosEventDispatcher dispatcher;

    private final JLabel amountDueLabel = new JLabel("Amount Due: $0.00", SwingConstants.LEFT);
    private final JTextField cashReceivedField = new JTextField();
    private final JLabel statusLabel = new JLabel(" ", SwingConstants.LEFT);

    private final JButton exactAmountButton = new JButton("Exact Amount");
    private final JButton nextDollarButton = new JButton("Next Dollar");
    private final JButton confirmButton = new JButton("Confirm");
    private final JButton cancelButton = new JButton("Cancel");

    /**
     * @param owner      the parent frame; may be {@code null}
     * @param dispatcher target for view-input events; must not be {@code null}
     */
    public PayWithCashView(JFrame owner, IPosEventDispatcher dispatcher) {
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        this.dispatcher = dispatcher;
        this.dialog = new JDialog(owner, "Pay Cash", true);
        this.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        buildLayout();
        wireActions();

        dialog.pack();
        dialog.setLocationRelativeTo(owner);
    }

    // ---- Public API called by PayWithCashViewController --------------------

    /** Updates the read-only {@code Amount Due} label. */
    public void setAmountDue(BigDecimal amount) {
        if (amount == null) throw new IllegalArgumentException("amount must not be null");
        amountDueLabel.setText("Amount Due: $" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    /** Populates the {@code Cash Received} field with the given text. */
    public void setCashReceivedText(String text) {
        cashReceivedField.setText(text == null ? "" : text);
    }

    /** @return the current contents of the {@code Cash Received} field, never {@code null} */
    public String getCashReceivedText() {
        String text = cashReceivedField.getText();
        return text == null ? "" : text;
    }

    /** Displays the change due to the customer via a modal message before the dialog closes. */
    public void showChangeDue(BigDecimal change) {
        if (change == null) throw new IllegalArgumentException("change must not be null");
        String message = "Change due: $" + change.setScale(2, RoundingMode.HALF_UP).toPlainString();
        statusLabel.setText(message);
        JOptionPane.showMessageDialog(dialog, message, "Change Due", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Shows an inline error message on the dialog (does not close it). */
    public void showError(String message) {
        statusLabel.setText(message == null ? " " : message);
    }

    /** Clears any inline status/error message. */
    public void clearStatus() {
        statusLabel.setText(" ");
    }

    /** Opens the dialog. Blocks until the dialog is closed (modal). */
    public void openDialog() {
        dialog.setVisible(true);
    }

    /** Hides the dialog. */
    public void closeDialog() {
        dialog.setVisible(false);
    }

    // ---- Layout ------------------------------------------------------------

    private void buildLayout() {
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        amountDueLabel.setFont(amountDueLabel.getFont().deriveFont(Font.BOLD, 18f));
        content.add(amountDueLabel, BorderLayout.NORTH);

        JPanel middle = new JPanel(new BorderLayout(0, 6));
        JLabel fieldLabel = new JLabel("Cash Received: $");
        JPanel fieldRow = new JPanel(new BorderLayout(6, 0));
        fieldRow.add(fieldLabel, BorderLayout.WEST);
        fieldRow.add(cashReceivedField, BorderLayout.CENTER);
        middle.add(fieldRow, BorderLayout.NORTH);

        JPanel quickFills = new JPanel(new GridLayout(1, 2, 6, 0));
        quickFills.add(exactAmountButton);
        quickFills.add(nextDollarButton);
        middle.add(quickFills, BorderLayout.CENTER);

        statusLabel.setForeground(java.awt.Color.RED.darker());
        middle.add(statusLabel, BorderLayout.SOUTH);
        content.add(middle, BorderLayout.CENTER);

        JPanel south = new JPanel(new GridLayout(1, 2, 6, 0));
        south.add(cancelButton);
        south.add(confirmButton);
        content.add(south, BorderLayout.SOUTH);

        dialog.getContentPane().add(content);
    }

    private void wireActions() {
        exactAmountButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CASH_EXACT_PRESSED)));
        nextDollarButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CASH_NEXT_DOLLAR_PRESSED)));
        confirmButton.addActionListener(e -> {
            Map<String, Object> props = new HashMap<>();
            props.put("cashReceived", getCashReceivedText());
            dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CASH_CONFIRM_PRESSED, props));
        });
        cancelButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CASH_CANCEL_PRESSED)));
    }
}
