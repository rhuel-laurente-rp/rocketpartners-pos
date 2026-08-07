package com.rocketpartners.onboarding.possystem.display;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Modal "are you sure?" dialog. Shares chrome with {@link ErrorDialog} — same header strip, same
 * footer, same keyboard bindings — so a cashier who reads one has already read the other.
 *
 * <p>The confirmation dialog is deliberately more visually alarming than a plain
 * {@code ok/cancel}: a {@link PosTheme#STOP}-tinted chip in place of the error dialog's bang
 * chip, and the primary button uses the danger palette when {@link #configure(String, String,
 * String, boolean)} is called with {@code destructive = true}. The point is to make a "click
 * through the modal" muscle-memory press feel wrong.</p>
 *
 * <p>Not a subclass of {@link ErrorDialog} — the two happen to look similar today but they mean
 * different things and are likely to diverge (Undo action, a "don't show again" checkbox, etc.).
 * The tiny bit of copy-paste is cheaper than an inheritance chain a future edit has to
 * navigate around.</p>
 */
class ConfirmDialog extends PosDialog {

    private final JLabel messageLabel = new JLabel();
    private final PosButton confirmButton;
    private final PosButton cancelButton;

    /** Set by {@link #configure} and consumed by the primary click. */
    private Runnable onConfirm = () -> {};

    /**
     * @param owner the parent frame; may be {@code null}
     */
    ConfirmDialog(JFrame owner) {
        super(owner, "Confirm");
        setBody(buildBody());

        cancelButton = PosButtons.danger("Cancel");
        cancelButton.addActionListener(e -> closeDialog());
        addSecondary(cancelButton);
        setCancelAction(this::closeDialog);

        // Start with a benign primary; configure() replaces it with a destructive one when the
        // caller flags the action.
        confirmButton = PosButtons.primary("Confirm");
        confirmButton.addActionListener(e -> {
            closeDialog();
            onConfirm.run();
        });
        setPrimary(confirmButton);
        // Focus goes to Cancel so an accidental Enter does not blow the basket away — the
        // cashier has to actively move to Confirm.
        setInitialFocus(cancelButton);
        setMinimumSize(new Dimension(460, 220));
    }

    /**
     * Configures the dialog for one confirmation prompt.
     *
     * @param title       header strip title
     * @param message     body text; a short sentence, plain language
     * @param confirmText label on the primary button (e.g. {@code "Void basket"})
     * @param destructive when true, primary uses the {@link PosButtons#danger} palette; use for
     *                    voids, deletions, and other actions the cashier can't undo
     * @param onConfirm   runnable executed after the user confirms and the dialog closes
     */
    void configure(String title, String message, String confirmText,
                   boolean destructive, Runnable onConfirm) {
        setDialogTitle(title == null ? "Confirm" : title);
        messageLabel.setText(message == null ? "" : "<html>" + escapeHtml(message) + "</html>");
        confirmButton.setText(confirmText == null ? "Confirm" : confirmText);
        // Re-pack the button to match the new label width — the dialog's own pack() during
        // openDialog() will cover the rest.
        confirmButton.setPreferredSize(null);
        confirmButton.invalidate();
        this.onConfirm = onConfirm == null ? () -> {} : onConfirm;
        // Danger vs. benign is a colour swap on the confirm button; keep the layout identical so
        // the dialog's chrome stays uniform across variants.
        if (destructive) {
            replacePrimary(PosButtons.danger(confirmText == null ? "Confirm" : confirmText));
        } else {
            replacePrimary(PosButtons.primary(confirmText == null ? "Confirm" : confirmText));
        }
    }

    private void replacePrimary(PosButton newPrimary) {
        newPrimary.addActionListener(e -> {
            closeDialog();
            onConfirm.run();
        });
        setPrimary(newPrimary);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
    }

    private JComponent buildBody() {
        JPanel body = new JPanel(new BorderLayout(16, 0));
        body.setOpaque(false);

        AlertChip chip = new AlertChip();
        chip.setPreferredSize(new Dimension(44, 44));
        chip.setMinimumSize(chip.getPreferredSize());
        JPanel chipWrap = new JPanel();
        chipWrap.setOpaque(false);
        chipWrap.setLayout(new BoxLayout(chipWrap, BoxLayout.Y_AXIS));
        chipWrap.add(chip);
        chipWrap.add(Box.createVerticalGlue());
        body.add(chipWrap, BorderLayout.WEST);

        messageLabel.setFont(PosTheme.base(Font.PLAIN, PosTheme.ROW));
        messageLabel.setForeground(PosTheme.INK);
        messageLabel.setVerticalAlignment(JLabel.TOP);
        messageLabel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        body.add(messageLabel, BorderLayout.CENTER);
        return body;
    }

    /** STOP-tinted round chip with a warning triangle, drawn rather than shipped as an image. */
    private static class AlertChip extends JComponent {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int r = Math.min(w, h) / 2;

            g2.setColor(new Color(0xFD, 0xF1, 0xEF));
            g2.fillOval(w / 2 - r, h / 2 - r, r * 2, r * 2);
            g2.setColor(PosTheme.STOP);
            g2.setStroke(new java.awt.BasicStroke(2f));
            g2.drawOval(w / 2 - r + 1, h / 2 - r + 1, r * 2 - 2, r * 2 - 2);

            g2.setColor(PosTheme.STOP);
            g2.setFont(PosTheme.base(Font.BOLD, r * 1.4f));
            String glyph = "?";
            java.awt.FontMetrics fm = g2.getFontMetrics();
            int tx = w / 2 - fm.stringWidth(glyph) / 2;
            int ty = h / 2 - (fm.getAscent() + fm.getDescent()) / 2 + fm.getAscent();
            g2.drawString(glyph, tx, ty);
            g2.dispose();
        }
    }
}
