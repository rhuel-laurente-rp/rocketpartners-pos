package com.rocketpartners.onboarding.possystem.display;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * A flat, rounded button that owns its own painting so the primary / secondary / danger /
 * tender hierarchy is legible regardless of the platform L&F.
 *
 * <p>Stock {@link JButton} disabled styling is too subtle to be read at arm's length across a
 * register, so this class draws its own {@link PosTheme#DISABLED_BG} fill and
 * {@link PosTheme#DISABLED_FG} text when disabled — the design-system requirement about
 * visibly-disabled controls lives here.</p>
 *
 * <p>Instances are usually built through the factory methods in {@link PosButtons}; the
 * constructor is exposed so a custom-drawn subclass (see {@code QuickAddTile}) can pass its own
 * palette in.</p>
 */
class PosButton extends JButton {

    private static final int ARC = 8;

    private final Color bg;
    private final Color fg;

    PosButton(String text, Color bg, Color fg, Font font) {
        super(text);
        this.bg = bg;
        this.fg = fg;
        setFont(font);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color fill = !isEnabled() ? PosTheme.DISABLED_BG
                : getModel().isPressed() ? shade(bg, 0.88f)
                : getModel().isRollover() ? shade(bg, 1.08f)
                : bg;
        g2.setColor(fill);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
        if (!isEnabled()) {
            g2.setColor(PosTheme.RULE);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
        }
        g2.dispose();
        setForeground(isEnabled() ? fg : PosTheme.DISABLED_FG);
        super.paintComponent(g);
    }

    static Color shade(Color c, float factor) {
        return new Color(
                Math.min(255, Math.round(c.getRed() * factor)),
                Math.min(255, Math.round(c.getGreen() * factor)),
                Math.min(255, Math.round(c.getBlue() * factor)));
    }
}
