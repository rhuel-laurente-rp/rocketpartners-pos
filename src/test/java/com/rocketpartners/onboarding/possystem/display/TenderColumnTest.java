package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Tender-column invariants: three buttons disabled at construction (Total hasn't been pressed
 * yet), each carrying its own tender colour when enabled, and all three collapsing to a shared
 * flat fill when disabled — no residual hue leak from the tender palette.
 *
 * <p>Disable/enable state is a view-side reflection of transaction phase; the enforcement lives
 * in {@code TransactionService}. These tests confirm the view reflects that state clearly, not
 * that the service prevents input.</p>
 */
class TenderColumnTest {

    @Test
    void allThreeTenderButtons_disabledAtConstruction() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            for (PosButton b : view.getTenderButtonsForTest()) {
                assertThat(b.isEnabled())
                        .as("tender %s must be disabled before Total is pressed", b.getText())
                        .isFalse();
            }
        } finally {
            view.dispose();
        }
    }

    @Test
    void enabling_leavesEachTenderCarryingItsOwnFill() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            view.setTenderInputEnabled(true);
            PosButton[] buttons = view.getTenderButtonsForTest();
            assertThat(buttons).extracting(PosButton::isEnabled).containsExactly(true, true, true);

            // Each carries a distinct fill — the whole point of the redesign. Assert on the
            // theme tokens rather than the RGB tuples so a palette shift updates one place.
            assertThat(buttons[0].getFillColor()).isEqualTo(PosTheme.GO);
            assertThat(buttons[1].getFillColor()).isEqualTo(PosTheme.CARD_DEBIT);
            assertThat(buttons[2].getFillColor()).isEqualTo(PosTheme.CARD_CREDIT);

            // And the three fills are actually distinct — a defensive check against a future
            // token rename accidentally aliasing two of them.
            assertThat(buttons[0].getFillColor()).isNotEqualTo(buttons[1].getFillColor());
            assertThat(buttons[1].getFillColor()).isNotEqualTo(buttons[2].getFillColor());
            assertThat(buttons[0].getFillColor()).isNotEqualTo(buttons[2].getFillColor());
        } finally {
            view.dispose();
        }
    }

    @Test
    void disabled_rendersFlatDisabledFillWithNoTenderTrace() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            // Disabled is the default state — tender stays dead until Total.
            for (PosButton b : view.getTenderButtonsForTest()) {
                b.setSize(200, PosTheme.BUTTON_HEIGHT_PRIMARY + PosButton.SHADOW_INSET);
                BufferedImage img = renderToImage(b);
                assertHasPixelMatching(img, PosTheme.DISABLED_BG,
                        b.getText() + " must render the shared DISABLED_BG fill when disabled");
                assertHasNoPixelMatching(img, b.getFillColor(),
                        b.getText() + " must not leak its tender colour into the disabled paint");
            }
        } finally {
            view.dispose();
        }
    }

    @Test
    void enabled_rendersItsOwnFill() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            view.setTenderInputEnabled(true);
            for (PosButton b : view.getTenderButtonsForTest()) {
                b.setSize(200, PosTheme.BUTTON_HEIGHT_PRIMARY + PosButton.SHADOW_INSET);
                BufferedImage img = renderToImage(b);
                assertHasPixelMatching(img, b.getFillColor(),
                        b.getText() + " must render its own tender fill when enabled");
                assertHasNoPixelMatching(img, PosTheme.DISABLED_BG,
                        b.getText() + " must not paint the disabled fill when it's live");
            }
        } finally {
            view.dispose();
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static BufferedImage renderToImage(PosButton button) {
        int w = button.getWidth();
        int h = button.getHeight();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            button.paint(g);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void assertHasPixelMatching(BufferedImage img, Color target, String reason) {
        int rgb = target.getRGB();
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == (rgb & 0xFFFFFF)) return;
            }
        }
        throw new AssertionError(reason);
    }

    private static void assertHasNoPixelMatching(BufferedImage img, Color target, String reason) {
        int rgb = target.getRGB() & 0xFFFFFF;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == rgb) {
                    throw new AssertionError(reason + " (matched at " + x + "," + y + ")");
                }
            }
        }
    }

    private static IPosEventDispatcher noop() {
        return event -> {};
    }
}
