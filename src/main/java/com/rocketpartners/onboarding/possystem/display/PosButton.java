package com.rocketpartners.onboarding.possystem.display;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;

/**
 * A flat, rounded button that owns its own painting so the primary / secondary / danger /
 * tender hierarchy is legible regardless of the platform L&amp;F.
 *
 * <p>Stock {@link JButton} disabled styling is too subtle to be read at arm's length across a
 * register, so this class draws its own {@link PosTheme#DISABLED_BG} fill and
 * {@link PosTheme#DISABLED_FG} text when disabled — the design-system requirement about
 * visibly-disabled controls lives here.</p>
 *
 * <p><strong>Elevation, resting state.</strong> The POS runs on a touchscreen, so in production
 * the hover state is never seen — a finger arrives directly at pressed. Resting therefore
 * carries the entire affordance burden. Four layers, painted in this order:</p>
 * <ol>
 *   <li>Two concentric translucent-black drop shadows offset 2 px down, alphas from
 *       {@link PosTheme#BUTTON_SHADOW_ALPHA_INNER} / {@link PosTheme#BUTTON_SHADOW_ALPHA_OUTER}.</li>
 *   <li>Flat body fill in the button's base colour.</li>
 *   <li>A {@link PosTheme#BUTTON_LIP_HEIGHT} px lip band along the inside of the bottom edge in
 *       the base colour darkened by {@link PosTheme#BUTTON_LIP_SHADE}, clipped to the rounded
 *       rect. The lip — not the shadow — is what actually reads as thickness; a shadow alone
 *       reads as a floating card, not a physical key.</li>
 *   <li>A 1 px inside-top-edge highlight in translucent white, painted only when the base fill
 *       is dark enough for it to register. Skipped on the pale secondary / danger tints where
 *       it would just look like a paint smear.</li>
 * </ol>
 *
 * <p><strong>Pressed state.</strong> The whole illusion collapses immediately — no shadow, no
 * lip, label translated 2 px down. On a touchscreen this is the only feedback the cashier gets,
 * so it must be instantaneous. No animation.</p>
 *
 * <p><strong>Disabled state.</strong> Flat. No shadow, no lip, no highlight —
 * {@link PosTheme#DISABLED_BG} fill with a hairline. Flatness itself becomes the signal that a
 * control is dead, which reads clearly next to the elevated live buttons around it.</p>
 *
 * <p>Instances are usually built through the factory methods in {@link PosButtons}; the
 * constructor is exposed so a custom-drawn subclass (see {@code QuickAddTile}) can pass its own
 * palette in.</p>
 *
 * <p><strong>Paint performance.</strong> Every derived colour — the two shadow layers, the lip
 * shade, the top highlight, the hover fill — is precomputed in the constructor and stored on
 * this instance. {@link #paintComponent(Graphics)} allocates no {@link Color},
 * {@link java.awt.BasicStroke}, or {@link Font} because Swing repaints these on every hover,
 * press, and focus change.</p>
 */
class PosButton extends JButton {

    /** Space reserved beneath the fill so the drop shadow has room to render before the border. */
    static final int SHADOW_INSET = 3;

    /** How far the pressed label translates. Matches the removed shadow depth so the button
     *  visibly compresses onto its own resting outline. */
    static final int PRESSED_SINK = 2;

    private final Color bg;
    private final Color fg;

    /**
     * Minimum touch-target height enforced by {@link #getPreferredSize()} — includes the fill
     * plus {@link #SHADOW_INSET} so the visible face measures the requested touch height.
     * Set by the factory in {@link PosButtons} or by {@link #setTouchMinHeight}.
     */
    private int touchMinHeight;

    // ---- Precomputed paint state ------------------------------------------
    // Everything below is derived from bg once in the constructor. paintComponent must not
    // allocate.

    private final Color shadowInner;
    private final Color shadowOuter;
    private final Color lipColor;
    private final Color hoverFill;
    private final Color topHighlight;
    private final boolean paintTopHighlight;

    PosButton(String text, Color bg, Color fg, Font font) {
        super(text);
        this.bg = bg;
        this.fg = fg;
        this.shadowInner = new Color(0, 0, 0, PosTheme.BUTTON_SHADOW_ALPHA_INNER);
        this.shadowOuter = new Color(0, 0, 0, PosTheme.BUTTON_SHADOW_ALPHA_OUTER);
        this.lipColor = PosTheme.shade(bg, PosTheme.BUTTON_LIP_SHADE);
        this.hoverFill = PosTheme.shade(bg, 1.06f);
        this.topHighlight = new Color(255, 255, 255, PosTheme.BUTTON_TOP_HIGHLIGHT_ALPHA);
        this.paintTopHighlight = PosTheme.isDarkFill(bg);

        setFont(font);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10 + SHADOW_INSET, 12));
        // Rollover tracking is left on so the mouse-based dev experience still gets the subtle
        // fill lightening on hover; a touchscreen never generates rollover events.
        setRolloverEnabled(true);
    }

    /**
     * Sets the minimum touch-target height (fill, not counting the shadow inset). Layouts read
     * this through {@link #getPreferredSize()} which reserves {@code height + SHADOW_INSET}
     * pixels total, so the button's visible face measures exactly the requested value.
     */
    void setTouchMinHeight(int height) {
        this.touchMinHeight = height;
        invalidate();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        if (touchMinHeight > 0) {
            int required = touchMinHeight + SHADOW_INSET;
            if (d.height < required) d.height = required;
        }
        return d;
    }

    /**
     * Test seam: {@code paintComponent} is {@code protected} on {@link javax.swing.JComponent},
     * and Swing wraps the outer {@link javax.swing.JComponent#paint(Graphics)} in a child
     * graphics that swallows Mockito spies. Tests call this directly to observe the raw
     * translate calls on their own graphics.
     */
    void paintComponentForTest(Graphics g) {
        paintComponent(g);
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
        int arc = PosTheme.BUTTON_CORNER_RADIUS;
        int fillH = h - SHADOW_INSET;

        if (!enabled) {
            // Flat: fill, hairline, no shadow/lip/highlight. Flatness signals "dead".
            g2.setColor(PosTheme.DISABLED_BG);
            g2.fillRoundRect(0, 0, w, fillH, arc, arc);
            g2.setColor(PosTheme.RULE);
            g2.drawRoundRect(0, 0, w - 1, fillH - 1, arc, arc);
            g2.dispose();
            setForeground(PosTheme.DISABLED_FG);
            super.paintComponent(g);
            return;
        }

        if (!pressed) {
            // 1. Drop shadow — two concentric stamps offset BUTTON_SHADOW_OFFSET pixels down.
            //    The outer stamp sits one pixel below the inner so the edge feathers rather than
            //    banding.
            int offset = PosTheme.BUTTON_SHADOW_OFFSET;
            g2.setColor(shadowOuter);
            g2.fillRoundRect(0, offset + 1, w, fillH, arc, arc);
            g2.setColor(shadowInner);
            g2.fillRoundRect(0, offset, w, fillH, arc, arc);
        }

        // 2. Body fill — flat.
        g2.setColor(pressed ? bg : (hover ? hoverFill : bg));
        g2.fillRoundRect(0, 0, w, fillH, arc, arc);

        if (!pressed) {
            // 3. Bottom lip — clipped to the lip band so we can reuse the rounded-rect stroke of
            //    the body fill without painting into the whole face.
            Shape prevClip = g2.getClip();
            g2.clipRect(0, fillH - PosTheme.BUTTON_LIP_HEIGHT, w, PosTheme.BUTTON_LIP_HEIGHT);
            g2.setColor(lipColor);
            g2.fillRoundRect(0, 0, w, fillH, arc, arc);
            g2.setClip(prevClip);

            // 4. Top highlight — only on dark fills.
            if (paintTopHighlight) {
                g2.setColor(topHighlight);
                g2.drawLine(arc / 2, 1, w - arc / 2, 1);
            }
        }

        g2.dispose();
        setForeground(fg);
        // Sink the label 2 px on press so the compression is felt, not just seen. No animation
        // — a touchscreen cashier gets one frame of feedback and needs it now.
        if (pressed) {
            g.translate(0, PRESSED_SINK);
            super.paintComponent(g);
            g.translate(0, -PRESSED_SINK);
        } else {
            super.paintComponent(g);
        }
    }
}
