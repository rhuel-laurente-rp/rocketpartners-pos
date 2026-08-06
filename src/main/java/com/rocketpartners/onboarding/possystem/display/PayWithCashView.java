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
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Modal cash-entry dialog: {@link PosDialog}-shelled, dumb, and register-shaped.
 *
 * <p>Body layout, top to bottom:</p>
 * <ul>
 *   <li>{@code AMOUNT DUE} eyebrow above the total at {@link PosTheme#DISPLAY} weight,
 *       right-aligned so it reads like a register readout.</li>
 *   <li>{@code CASH RECEIVED} eyebrow above a single-line field at
 *       {@link PosTheme#HEADLINE}, right-aligned.</li>
 *   <li>Two secondary chips directly beneath the field — {@code Exact amount} and
 *       {@code Next dollar} — that fill the field without tendering.</li>
 *   <li>A {@link PosTheme#SELECTED}-tinted {@code Change due} strip that is hidden until the
 *       controller calls {@link #showChangeDue(BigDecimal)}, so the dialog doesn't display a
 *       meaningless $0.00 on open.</li>
 *   <li>Inline error strip in {@link PosTheme#STOP} for validation failures.</li>
 * </ul>
 *
 * <p>Footer: {@code Cancel} on the left ({@link PosButtons#secondary(String)}),
 * {@code Confirm payment} on the right ({@link PosButtons#primary(String)}, 48 px tall).</p>
 */
public class PayWithCashView extends PosDialog {

    private final IPosEventDispatcher dispatcher;

    private final JLabel amountDueValue = new JLabel("$0.00", SwingConstants.RIGHT);
    private final JTextField cashReceivedField = new JTextField();
    private final JLabel statusLabel = new JLabel(" ");
    private final JPanel changeStrip = new JPanel(new BorderLayout());
    private final JLabel changeValue = new JLabel("$0.00", SwingConstants.RIGHT);

    /**
     * @param owner      the parent frame; may be {@code null}
     * @param dispatcher target for view-input events; must not be {@code null}
     */
    public PayWithCashView(JFrame owner, IPosEventDispatcher dispatcher) {
        super(owner, "Pay cash");
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        this.dispatcher = dispatcher;

        setBody(buildBody());

        PosButton confirm = PosButtons.primary("Confirm payment");
        confirm.addActionListener(e -> {
            Map<String, Object> props = new HashMap<>();
            props.put("cashReceived", getCashReceivedText());
            dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CASH_CONFIRM_PRESSED, props));
        });
        setPrimary(confirm);

        PosButton cancel = PosButtons.secondary("Cancel");
        cancel.addActionListener(e -> fireCancel());
        addSecondary(cancel);
        setCancelAction(this::fireCancel);
        setInitialFocus(cashReceivedField);
    }

    private void fireCancel() {
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CASH_CANCEL_PRESSED));
    }

    // ---- Public API called by PayWithCashViewController --------------------

    public void setAmountDue(BigDecimal amount) {
        if (amount == null) throw new IllegalArgumentException("amount must not be null");
        amountDueValue.setText("$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    public void setCashReceivedText(String text) {
        cashReceivedField.setText(text == null ? "" : text);
    }

    public String getCashReceivedText() {
        String text = cashReceivedField.getText();
        return text == null ? "" : text;
    }

    /** Shows the change-due strip with the given amount. */
    public void showChangeDue(BigDecimal change) {
        if (change == null) throw new IllegalArgumentException("change must not be null");
        changeValue.setText("$" + change.setScale(2, RoundingMode.HALF_UP).toPlainString());
        changeStrip.setVisible(true);
    }

    /** Shows an inline error message on the dialog (does not close it). */
    public void showError(String message) {
        statusLabel.setText(message == null ? " " : message);
        statusLabel.setVisible(message != null && !message.isEmpty());
    }

    /** Clears any inline status/error message and hides the change strip. */
    public void clearStatus() {
        statusLabel.setText(" ");
        statusLabel.setVisible(false);
        changeStrip.setVisible(false);
    }

    // ---- Layout -----------------------------------------------------------

    private JPanel buildBody() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        body.add(section("AMOUNT DUE",
                configureReadout(amountDueValue, PosTheme.DISPLAY, PosTheme.INK)));
        body.add(Box.createVerticalStrut(18));

        // Cash received row.
        cashReceivedField.setFont(PosTheme.base(Font.BOLD, PosTheme.HEADLINE));
        cashReceivedField.setHorizontalAlignment(SwingConstants.RIGHT);
        cashReceivedField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PosTheme.RULE, 1),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));
        cashReceivedField.setPreferredSize(new Dimension(280, 56));
        body.add(section("CASH RECEIVED", cashReceivedField));
        body.add(Box.createVerticalStrut(10));

        // Chips row.
        JPanel chips = new JPanel(new GridLayout(1, 2, 8, 0));
        chips.setOpaque(false);
        PosButton exactChip = PosButtons.secondary("Exact amount");
        exactChip.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CASH_EXACT_PRESSED)));
        PosButton nextDollarChip = PosButtons.secondary("Next dollar");
        nextDollarChip.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CASH_NEXT_DOLLAR_PRESSED)));
        chips.add(exactChip);
        chips.add(nextDollarChip);
        chips.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(chips);
        body.add(Box.createVerticalStrut(14));

        // Change-due strip: SELECTED tint, hidden by default.
        changeStrip.setBackground(PosTheme.SELECTED);
        changeStrip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xC5, 0xDD, 0xD1), 1),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        JLabel changeKey = new JLabel("CHANGE DUE");
        changeKey.setFont(PosTheme.eyebrow());
        changeKey.setForeground(PosTheme.GO);
        changeValue.setFont(PosTheme.base(Font.BOLD, PosTheme.AMOUNT));
        changeValue.setForeground(PosTheme.INK);
        changeStrip.add(changeKey, BorderLayout.WEST);
        changeStrip.add(changeValue, BorderLayout.EAST);
        changeStrip.setVisible(false);
        changeStrip.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(changeStrip);

        // Inline error, hidden until populated.
        statusLabel.setFont(PosTheme.base(Font.PLAIN, PosTheme.BODY));
        statusLabel.setForeground(PosTheme.STOP);
        statusLabel.setVisible(false);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(Box.createVerticalStrut(8));
        body.add(statusLabel);

        return body;
    }

    private static JLabel configureReadout(JLabel label, float size, Color colour) {
        label.setFont(PosTheme.base(Font.BOLD, size));
        label.setForeground(colour);
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        return label;
    }

    private static JPanel section(String eyebrow, java.awt.Component body) {
        JPanel wrap = new JPanel(new BorderLayout(0, 6));
        wrap.setOpaque(false);
        JLabel label = new JLabel(eyebrow);
        label.setFont(PosTheme.eyebrow());
        label.setForeground(PosTheme.MUTED);
        wrap.add(label, BorderLayout.NORTH);
        wrap.add(body, BorderLayout.CENTER);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        return wrap;
    }
}
