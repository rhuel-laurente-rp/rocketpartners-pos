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
 * tender hierarchy is legible regardless of the platform L&amp;F.
 *
 * <p>Stock {@link JButton} disabled styling is too subtle to be read at arm's length across a
 * register, so this class draws its own {@link PosTheme#DISABLED_BG} fill and
 * {@link PosTheme#DISABLED_FG} text when disabled — the design-system requirement about
 * visibly-disabled controls lives here.</p>
 *
 * <p><strong>Elevation.</strong> Flat rectangles don't read as pressable at a glance. Each
 * enabled button paints a soft drop shadow — three concentric translucent black rounded rects
 * offset 1–2px below the fill — plus a 1px lighter inner line along the top edge, so the button
 * looks lit from above. On hover the shadow grows by one pixel; on press the shadow disappears
 * and the label translates 1px down, so the button visibly sinks. Disabled buttons paint no
 * shadow at all — flatness becomes the signal that the control is dead. Keep the primitives
 * cheap; this repaints on every hover.</p>
 *
 * <p>Instances are usually built through the factory methods in {@link PosButtons}; the
 * constructor is exposed so a custom-drawn subclass (see {@code QuickAddTile}) can pass its own
 * palette in.</p>
 */
class PosButton extends JButton {

    private static final int ARC = 8;

    /** Space reserved beneath the fill so the shadow doesn't get clipped. */
    static final int SHADOW_INSET = 3;

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
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10 + SHADOW_INSET, 12));
        // Rollovers must be tracked to repaint on hover state changes so the shadow can grow.
        setRolloverEnabled(true);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        boolean enabled = isEnabled();
        boolean pressed = enabled && getModel().isPressed();
        boolean hover = enabled && getModel().isRollover() && !pressed;

        int w = getWidth();
        int h = getHeight();

        // Shadow: three concentric stamps beneath the fill, denser closest to the button, so
        // the edge has real gradient rather than a hard band. Skipped entirely when disabled
        // or pressed — pressed buttons should look sunk into the surface.
        if (enabled && !pressed) {
            int reach = hover ? 3 : 2;
            for (int i = reach; i >= 1; i--) {
                int alpha = hover ? (8 + i * 4) : (6 + i * 3);
                g2.setColor(new Color(0, 0, 0, alpha));
                g2.fillRoundRect(1, i, w - 2, h - SHADOW_INSET - 1 + i - 1, ARC, ARC);
            }
        }

        // Sink the label on press so the button visibly compresses.
        int fillY = pressed ? 1 : 0;
        int fillH = h - SHADOW_INSET;
        Color fill = !enabled ? PosTheme.DISABLED_BG
                : pressed ? shade(bg, 0.92f)
                : hover ? shade(bg, 1.06f)
                : bg;
        g2.setColor(fill);
        g2.fillRoundRect(0, fillY, w, fillH, ARC, ARC);

        if (enabled && !pressed) {
            // Highlight line along the top edge, so the fill reads as a slightly domed cap
            // catching light — the second half of the "lit from above" cue.
            g2.setColor(new Color(255, 255, 255, 70));
            g2.drawLine(ARC / 2, 1, w - ARC / 2, 1);
        }

        if (!enabled) {
            g2.setColor(PosTheme.RULE);
            g2.drawRoundRect(0, 0, w - 1, fillH - 1, ARC, ARC);
        }
        g2.dispose();
        setForeground(enabled ? fg : PosTheme.DISABLED_FG);
        // Sink the label with the fill on press so the compression is felt, not just seen.
        if (pressed) {
            g.translate(0, 1);
            super.paintComponent(g);
            g.translate(0, -1);
        } else {
            super.paintComponent(g);
        }
    }

    static Color shade(Color c, float factor) {
        return new Color(
                Math.min(255, Math.round(c.getRed() * factor)),
                Math.min(255, Math.round(c.getGreen() * factor)),
                Math.min(255, Math.round(c.getBlue() * factor)));
    }
}
