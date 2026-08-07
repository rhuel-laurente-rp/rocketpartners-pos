package com.rocketpartners.onboarding.possystem.display;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.swing.ButtonModel;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Rendering tests for {@link PosButton}'s three visual states.
 *
 * <p>The design brief says the pressed state must offset the label by
 * {@link PosButton#PRESSED_SINK} pixels and the disabled state must paint no shadow. These are
 * the two touchscreen-critical invariants — pressed feedback is the only affordance a cashier
 * gets on a real terminal, and a disabled button that still elevates reads as tappable when it
 * is not.</p>
 *
 * <p>The pressed-offset check spies on the {@link Graphics2D} handed to {@code paintComponent}
 * and verifies the translate call, rather than diffing pixel positions of the label glyph —
 * font metrics vary too much across platforms to make a pixel-position assertion portable, but
 * the translate call itself is a hard invariant of the paint contract.</p>
 */
class PosButtonPaintTest {

    private static final int W = 120;
    private static final int H = PosTheme.BUTTON_HEIGHT_PRIMARY + PosButton.SHADOW_INSET;

    @Test
    void disabled_paintsNoShadowAndNoLip() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");

        PosButton button = PosButtons.primary("Total");
        button.setEnabled(false);
        button.setSize(W, H);

        BufferedImage img = paint(button);

        // Pixels beneath the fill (shadow zone) must be exactly the background colour we filled
        // before painting — the disabled state paints no shadow into that band. The design tenet
        // is that flatness signals "dead"; a live-looking disabled control would misinform.
        int fillH = H - PosButton.SHADOW_INSET;
        for (int y = fillH; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int rgb = img.getRGB(x, y);
                assertThat(rgb)
                        .as("disabled button drew into shadow zone at (%d, %d)", x, y)
                        .isEqualTo(Color.WHITE.getRGB());
            }
        }
    }

    @Test
    void resting_paintsShadowBeneathFill() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");

        PosButton button = PosButtons.primary("Total");
        button.setSize(W, H);

        BufferedImage img = paint(button);

        // The resting state must lay a translucent shadow into the reserved band beneath the
        // fill. Any pixel darker than pure white in that band is evidence — the shadow is a
        // black stamp at low alpha, blended over the white background we prepped.
        int fillH = H - PosButton.SHADOW_INSET;
        boolean shadowFound = false;
        outer:
        for (int y = fillH; y < H; y++) {
            for (int x = W / 4; x < 3 * W / 4; x++) {
                if (img.getRGB(x, y) != Color.WHITE.getRGB()) {
                    shadowFound = true;
                    break outer;
                }
            }
        }
        assertThat(shadowFound)
                .as("resting-state primary must lay a drop shadow beneath the fill")
                .isTrue();
    }

    @Test
    void pressed_translatesLabelBySinkAmount() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");

        PosButton button = PosButtons.primary("Total");
        button.setSize(W, H);
        pressAndArm(button);

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D real = img.createGraphics();
        try {
            real.setColor(Color.WHITE);
            real.fillRect(0, 0, W, H);
            Graphics2D spy = Mockito.spy(real);
            button.paintComponentForTest(spy);
            // The pressed path in paintComponent must call g.translate(0, PRESSED_SINK) before
            // delegating to super.paintComponent — that is what sinks the label. It must also
            // translate back once painting is done so subsequent painting isn't offset.
            Mockito.verify(spy).translate(0, PosButton.PRESSED_SINK);
            Mockito.verify(spy).translate(0, -PosButton.PRESSED_SINK);
        } finally {
            real.dispose();
        }
    }

    @Test
    void resting_doesNotTranslateLabel() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");

        PosButton button = PosButtons.primary("Total");
        button.setSize(W, H);

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D real = img.createGraphics();
        try {
            real.setColor(Color.WHITE);
            real.fillRect(0, 0, W, H);
            Graphics2D spy = Mockito.spy(real);
            button.paintComponentForTest(spy);
            Mockito.verify(spy, Mockito.never()).translate(0, PosButton.PRESSED_SINK);
        } finally {
            real.dispose();
        }
    }

    @Test
    void factories_enforceTouchTargetMinimumHeight() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");

        assertThat(PosButtons.primary("x").getPreferredSize().height)
                .isGreaterThanOrEqualTo(PosTheme.BUTTON_HEIGHT_PRIMARY + PosButton.SHADOW_INSET);
        assertThat(PosButtons.tender("x", PosTheme.GO).getPreferredSize().height)
                .isGreaterThanOrEqualTo(PosTheme.BUTTON_HEIGHT_PRIMARY + PosButton.SHADOW_INSET);
        assertThat(PosButtons.secondary("x").getPreferredSize().height)
                .isGreaterThanOrEqualTo(PosTheme.BUTTON_HEIGHT_SECONDARY + PosButton.SHADOW_INSET);
        assertThat(PosButtons.danger("x").getPreferredSize().height)
                .isGreaterThanOrEqualTo(PosTheme.BUTTON_HEIGHT_SECONDARY + PosButton.SHADOW_INSET);
    }

    private static BufferedImage paint(PosButton button) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            button.paint(g);
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * Puts the button's model into the exact state Swing hands it during a real finger-down
     * press: armed (mouse-over-and-pressed) and pressed. Just calling {@code setPressed(true)}
     * is not enough on every L&amp;F — the model treats "pressed" without "armed" as a keyboard
     * key-down and some skins short-circuit before the paint contract runs.
     */
    private static void pressAndArm(PosButton button) {
        ButtonModel model = button.getModel();
        model.setArmed(true);
        model.setPressed(true);
    }
}
