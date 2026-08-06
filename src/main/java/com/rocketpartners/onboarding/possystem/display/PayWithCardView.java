package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.TenderType;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Modal "card processing" dialog: a dumb Swing renderer that shows the amount being charged
 * and a live status line ({@code Processing…} → {@code Approved}). One class serves both
 * {@link TenderType#DEBIT} and {@link TenderType#CREDIT} — the {@link PayWithCardViewController}
 * passes the tender type when opening.
 *
 * <p>The view itself has no user input — the controller drives the state transitions and closes
 * the dialog once approval is simulated. That's why there is no {@link
 * com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher} reference here.</p>
 */
public class PayWithCardView {

    private final JDialog dialog;

    private final JLabel titleLabel = new JLabel("Card Payment", SwingConstants.CENTER);
    private final JLabel amountLabel = new JLabel("$0.00", SwingConstants.CENTER);
    private final JLabel statusLabel = new JLabel("Processing…", SwingConstants.CENTER);

    /**
     * @param owner the parent frame; may be {@code null}
     */
    public PayWithCardView(JFrame owner) {
        this.dialog = new JDialog(owner, "Card Payment", true);
        this.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        this.dialog.setPreferredSize(new Dimension(320, 180));

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        amountLabel.setFont(amountLabel.getFont().deriveFont(Font.BOLD, 22f));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 14f));

        content.add(titleLabel, BorderLayout.NORTH);
        content.add(amountLabel, BorderLayout.CENTER);
        content.add(statusLabel, BorderLayout.SOUTH);

        dialog.getContentPane().add(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
    }

    // ---- Public API called by PayWithCardViewController --------------------

    /** Configures the dialog for the given tender type and amount before opening. */
    public void configure(TenderType tenderType, BigDecimal amount) {
        if (tenderType == null) throw new IllegalArgumentException("tenderType must not be null");
        if (amount == null) throw new IllegalArgumentException("amount must not be null");
        String label = tenderType == TenderType.DEBIT ? "Debit Payment" : "Credit Payment";
        titleLabel.setText(label);
        amountLabel.setText("$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    /** Sets the "processing" status text; called before the simulated delay. */
    public void showProcessing() {
        statusLabel.setText("Processing…");
    }

    /** Sets the "approved" status text; called after the simulated delay. */
    public void showApproved() {
        statusLabel.setText("Approved");
    }

    /** Opens the dialog. Blocks until the dialog is closed (modal). */
    public void openDialog() {
        dialog.setVisible(true);
    }

    /** Hides the dialog. */
    public void closeDialog() {
        dialog.setVisible(false);
    }
}
