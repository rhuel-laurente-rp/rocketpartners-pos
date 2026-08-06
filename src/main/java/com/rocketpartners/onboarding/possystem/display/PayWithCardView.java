package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.TenderType;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Modal card-payment dialog: one class for both {@link TenderType#DEBIT} and
 * {@link TenderType#CREDIT}, parameterized by the {@link #configure(TenderType, BigDecimal)}
 * call before opening.
 *
 * <p>The dialog has no user input — the controller opens it, calls {@link #showProcessing()},
 * schedules the simulated approval off the EDT, then calls {@link #showApproved()} and closes.
 * That's why there's no dispatcher reference here.</p>
 *
 * <p>Body: amount charged at {@link PosTheme#DISPLAY} weight; a status area that switches
 * between an indeterminate {@link JProgressBar} tinted {@link PosTheme#GO} plus
 * "Contacting processor…" and a large green check plus "Approved".</p>
 *
 * <p>Footer: a single primary button ("Confirm payment") that stays disabled throughout, to
 * reinforce that the cashier isn't the one committing the tender — the processor is.</p>
 */
public class PayWithCardView extends PosDialog {

    private final JLabel amountValue = new JLabel("$0.00", SwingConstants.CENTER);
    private final JLabel statusLabel = new JLabel("Contacting processor…", SwingConstants.CENTER);
    private final JProgressBar progress = new JProgressBar();
    private final CheckMark checkMark = new CheckMark();
    private final PosButton confirm = PosButtons.primary("Confirm payment");

    /**
     * @param owner the parent frame; may be {@code null}
     */
    public PayWithCardView(JFrame owner) {
        super(owner, "Pay debit");
        setBody(buildBody());
        confirm.setEnabled(false);
        setPrimary(confirm);
        setMinimumSize(new Dimension(420, 320));
    }

    // ---- Public API called by PayWithCardViewController --------------------

    public void configure(TenderType tenderType, BigDecimal amount) {
        if (tenderType == null) throw new IllegalArgumentException("tenderType must not be null");
        if (amount == null) throw new IllegalArgumentException("amount must not be null");
        String title = tenderType == TenderType.DEBIT ? "Pay debit" : "Pay credit";
        setDialogTitle(title);
        amountValue.setText("$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    public void showProcessing() {
        statusLabel.setText("Contacting processor…");
        statusLabel.setForeground(PosTheme.MUTED);
        progress.setIndeterminate(true);
        progress.setVisible(true);
        checkMark.setVisible(false);
        confirm.setEnabled(false);
    }

    public void showApproved() {
        statusLabel.setText("Approved");
        statusLabel.setForeground(PosTheme.GO);
        progress.setIndeterminate(false);
        progress.setVisible(false);
        checkMark.setVisible(true);
        // Confirm stays disabled — the transaction is already paid by the time this runs.
    }

    // ---- Layout -----------------------------------------------------------

    private JPanel buildBody() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        JLabel amountEyebrow = new JLabel("AMOUNT CHARGED", SwingConstants.CENTER);
        amountEyebrow.setFont(PosTheme.eyebrow());
        amountEyebrow.setForeground(PosTheme.MUTED);
        amountEyebrow.setAlignmentX(Component.CENTER_ALIGNMENT);

        amountValue.setFont(PosTheme.base(Font.BOLD, PosTheme.DISPLAY));
        amountValue.setForeground(PosTheme.INK);
        amountValue.setAlignmentX(Component.CENTER_ALIGNMENT);

        body.add(amountEyebrow);
        body.add(Box.createVerticalStrut(6));
        body.add(amountValue);
        body.add(Box.createVerticalStrut(24));

        // Progress bar styled GO. The exact colour comes from UIManager overrides so it works
        // under FlatLaf too; a plain setForeground would be ignored by some L&Fs.
        UIManager.put("ProgressBar.foreground", PosTheme.GO);
        UIManager.put("ProgressBar.selectionBackground", PosTheme.GO);
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(280, 8));
        progress.setBorderPainted(false);
        progress.setForeground(PosTheme.GO);
        progress.setBackground(PosTheme.DISABLED_BG);
        progress.setAlignmentX(Component.CENTER_ALIGNMENT);

        checkMark.setPreferredSize(new Dimension(64, 64));
        checkMark.setMaximumSize(checkMark.getPreferredSize());
        checkMark.setAlignmentX(Component.CENTER_ALIGNMENT);
        checkMark.setVisible(false);

        JPanel indicator = new JPanel();
        indicator.setOpaque(false);
        indicator.setLayout(new BoxLayout(indicator, BoxLayout.Y_AXIS));
        indicator.add(centered(progress));
        indicator.add(Box.createVerticalStrut(6));
        indicator.add(centered(checkMark));
        indicator.setAlignmentX(Component.CENTER_ALIGNMENT);
        body.add(indicator);
        body.add(Box.createVerticalStrut(14));

        statusLabel.setFont(PosTheme.base(Font.PLAIN, PosTheme.ROW));
        statusLabel.setForeground(PosTheme.MUTED);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        body.add(statusLabel);

        body.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        return body;
    }

    private static JPanel centered(JComponent c) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.add(Box.createHorizontalGlue());
        row.add(c);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    /** GO-coloured check mark, drawn rather than shipped as an image asset. */
    private static class CheckMark extends JComponent {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int r = Math.min(w, h) / 2;

            g2.setColor(PosTheme.GO);
            g2.fillOval(w / 2 - r, h / 2 - r, r * 2, r * 2);

            g2.setColor(java.awt.Color.WHITE);
            g2.setStroke(new java.awt.BasicStroke(4f, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            Path2D path = new Path2D.Float();
            int cx = w / 2, cy = h / 2;
            path.moveTo(cx - r * 0.4f, cy);
            path.lineTo(cx - r * 0.05f, cy + r * 0.35f);
            path.lineTo(cx + r * 0.45f, cy - r * 0.3f);
            g2.draw(path);
            g2.dispose();
        }
    }
}
