package com.rocketpartners.onboarding.possystem.display;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.font.TextAttribute;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Single source of truth for the POS design system: palette, type scale, and small layout
 * primitives that every view and dialog reuses.
 *
 * <p>Two rules for using this class:</p>
 * <ul>
 *   <li>No view may hard-code a colour or font size that overlaps with the tokens defined here.
 *       If a view needs a shade or a size that isn't in the theme, add it to the theme first,
 *       then consume it — so the vocabulary of the interface stays finite.</li>
 *   <li>Type is named by role, not by pixel count. {@code EYEBROW} is the letterspaced label
 *       that sits above cards, {@code AMOUNT} is the money read-out, {@code DISPLAY} is the
 *       largest number on screen. The int constants are here so a redesign is one edit.</li>
 * </ul>
 *
 * <p>The palette is deliberately register-hardware in feel — graphite chassis, warm
 * receipt-tape white, one saturated green reserved for pay actions — rather than a generic UI
 * kit. That belongs alongside the tokens so the choice is legible.</p>
 */
public final class PosTheme {

    private PosTheme() {}

    // ---- Palette -----------------------------------------------------------

    /** Near-black. Primary text, header strip, and heavy chrome. */
    public static final Color INK = new Color(0x14, 0x18, 0x1D);
    /** Warm off-white for the app-wide background. Reads as receipt tape, not screen white. */
    public static final Color PAPER = new Color(0xFB, 0xFA, 0xF7);
    /** Pure white for card and dialog body surfaces. */
    public static final Color SURFACE = Color.WHITE;
    /** Hairline colour used for card borders, summary rules, and disabled outlines. */
    public static final Color RULE = new Color(0xE2, 0xE0, 0xDA);
    /** Secondary label colour: metadata, unit prices, disabled hints. */
    public static final Color MUTED = new Color(0x6E, 0x73, 0x79);
    /** Saturated green reserved for pay-forward affirmative actions. */
    public static final Color GO = new Color(0x0B, 0x6E, 0x4F);
    /** Saturated red for destructive actions (void basket) and error accents. */
    public static final Color STOP = new Color(0xA3, 0x2A, 0x1F);
    /** Amber for "live / awaiting" states (tender enabled, processing card). */
    public static final Color LIVE = new Color(0xC9, 0x7A, 0x0E);
    /** Tint used on selected basket rows and change-due strip. */
    public static final Color SELECTED = new Color(0xEC, 0xF3, 0xF0);
    /** Row hover background — one step darker than SURFACE, still lighter than SELECTED. */
    public static final Color HOVER_ROW = new Color(0xF5, 0xF6, 0xF3);
    /** Muted rule inside the basket list between rows. */
    public static final Color ROW_RULE = new Color(0xF1, 0xEF, 0xEA);
    /** Fill for the compact quantity badge (non-voided). */
    public static final Color BADGE_BG = new Color(0x0B, 0x6E, 0x4F);
    /** Text colour on the compact quantity badge. */
    public static final Color BADGE_FG = Color.WHITE;
    /** Fill for disabled controls. */
    public static final Color DISABLED_BG = new Color(0xF0, 0xEF, 0xEB);
    /** Foreground for disabled control text. */
    public static final Color DISABLED_FG = new Color(0xA8, 0xAB, 0xAF);

    // ---- Type scale --------------------------------------------------------
    // Kept as float because Font.deriveFont(int, float) is the only signature that takes size.

    /** Small-caps eyebrow label above cards and card sections. Letterspaced. */
    public static final float EYEBROW = 11f;
    /** Body copy: dialog description, hints, secondary labels. */
    public static final float BODY = 13f;
    /** Basket row description, dialog input labels. */
    public static final float ROW = 15f;
    /** Button labels for primary/tender buttons. */
    public static final float BUTTON = 17f;
    /** Money read-outs on tender surfaces (Amount Due, Cash Received). */
    public static final float AMOUNT = 20f;
    /** Section headlines, dialog titles used inline. */
    public static final float HEADLINE = 28f;
    /** Largest number on screen: the grand total, register display. */
    public static final float DISPLAY = 40f;

    // ---- Font helpers ------------------------------------------------------

    /**
     * @param style {@link Font#PLAIN} / {@link Font#BOLD} / {@link Font#ITALIC}
     * @param size  point size — one of the named type-scale constants above
     * @return a font in the system's default UI family at the given style and size
     */
    public static Font base(int style, float size) {
        return new JLabel().getFont().deriveFont(style, size);
    }

    /** The eyebrow font: bold, letterspaced. */
    public static Font eyebrow() {
        return base(Font.BOLD, EYEBROW).deriveFont(trackedAttributes());
    }

    /**
     * Attributes used to letterspace small-caps eyebrow labels.
     *
     * @return a fresh mutable map (callers may add further attributes)
     */
    public static Map<TextAttribute, Object> trackedAttributes() {
        Map<TextAttribute, Object> attrs = new HashMap<>();
        attrs.put(TextAttribute.TRACKING, 0.12);
        return attrs;
    }

    // ---- Formatting helpers -----------------------------------------------

    /**
     * Money display format used everywhere in the UI. Rounds to scale 2 with HALF_UP for
     * display only — never mutates the underlying value.
     */
    public static String money(BigDecimal amount) {
        return "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // ---- Layout primitives ------------------------------------------------

    /**
     * A titled "card": {@code EYEBROW}-styled label above a {@link #SURFACE} panel with a
     * {@link #RULE} hairline border. Used everywhere in the main window for consistent
     * grouping.
     */
    public static JPanel card(String eyebrow, JComponent body) {
        JPanel wrap = new JPanel(new BorderLayout(0, 6));
        wrap.setOpaque(false);

        JLabel label = new JLabel(eyebrow.toUpperCase());
        label.setFont(eyebrow());
        label.setForeground(MUTED);
        wrap.add(label, BorderLayout.NORTH);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(SURFACE);
        panel.setBorder(BorderFactory.createLineBorder(RULE));
        panel.add(body, BorderLayout.CENTER);
        wrap.add(panel, BorderLayout.CENTER);
        return wrap;
    }
}
