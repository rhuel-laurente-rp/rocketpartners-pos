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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Modal error dialog built on {@link PosDialog}, replacing the earlier
 * {@link javax.swing.JOptionPane}-based popup.
 *
 * <p>The old design argued that JOptionPane was "the view that already exists" and wrapping it
 * was ceremony. That argument no longer holds now that every other surface shares a design
 * system: the one dialog a cashier sees under stress would be the one that looks like a
 * different application, which is exactly the wrong place to be inconsistent.</p>
 *
 * <p>Layout: a {@link PosTheme#STOP}-tinted round icon chip with a white bang, the error
 * message at {@link PosTheme#ROW} weight in plain language, and a single {@code Dismiss}
 * primary. Coalescing (no stacked errors) is enforced by
 * {@link ErrorPopupViewController}; this class knows nothing about it.</p>
 */
class ErrorDialog extends PosDialog {

    private final JLabel messageLabel = new JLabel();
    private final PosButton dismissButton;

    /**
     * @param owner the parent frame; may be {@code null}
     */
    ErrorDialog(JFrame owner) {
        super(owner, "Error");
        setBody(buildBody());
        dismissButton = PosButtons.primary("Dismiss");
        dismissButton.addActionListener(e -> closeDialog());
        setPrimary(dismissButton);
        setInitialFocus(dismissButton);
        setMinimumSize(new Dimension(440, 220));
    }

    /**
     * Sets the dialog title and message before opening. Reused across multiple errors, one at
     * a time — the controller opens one, waits for it to close, then opens the next.
     */
    void configure(String title, String message) {
        setDialogTitle(title == null ? "Error" : title);
        messageLabel.setText(message == null ? "" : "<html>" + escapeHtml(message) + "</html>");
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
    }

    private JComponent buildBody() {
        JPanel body = new JPanel(new BorderLayout(16, 0));
        body.setOpaque(false);

        StopChip chip = new StopChip();
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

    /** STOP-tinted round chip with a white bang, drawn rather than shipped as an image. */
    private static class StopChip extends JComponent {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int r = Math.min(w, h) / 2;

            g2.setColor(new Color(0xFD, 0xF1, 0xEF)); // pale STOP tint
            g2.fillOval(w / 2 - r, h / 2 - r, r * 2, r * 2);
            g2.setColor(PosTheme.STOP);
            g2.setStroke(new java.awt.BasicStroke(2f));
            g2.drawOval(w / 2 - r + 1, h / 2 - r + 1, r * 2 - 2, r * 2 - 2);

            g2.setColor(PosTheme.STOP);
            g2.setFont(PosTheme.base(Font.BOLD, r * 1.4f));
            String bang = "!";
            java.awt.FontMetrics fm = g2.getFontMetrics();
            int tx = w / 2 - fm.stringWidth(bang) / 2;
            int ty = h / 2 - (fm.getAscent() + fm.getDescent()) / 2 + fm.getAscent();
            g2.drawString(bang, tx, ty);
            g2.dispose();
        }
    }

    /** Convenience for a test to override the initial focus target. */
    @SuppressWarnings("unused")
    Component defaultFocusTarget() {
        return dismissButton;
    }
}
