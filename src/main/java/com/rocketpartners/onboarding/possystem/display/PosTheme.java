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
    /**
     * Violet — the buy-N-get-M "free item" / promo marker, on a basket free-row and on a Quick Add
     * tile whose UPC carries a {@code PROMO} rule. Deliberately none of the hues already carrying
     * meaning: {@link #GO} green is the pay-forward / hover / selection / newest-scan-flash colour (a
     * green promo tag would blend into those transient row states), {@link #LIVE} amber is the
     * processing/awaiting state, {@link #STOP} red is void/error, and blue/indigo
     * ({@link #CARD_DEBIT}/{@link #CARD_CREDIT}) are the tenders. Violet reads as a persistent "deal"
     * accent that a cashier won't confuse with any of those.
     */
    public static final Color PROMO = new Color(0x9D, 0x2E, 0xA8);
    /**
     * Azure — a Quick Add tile whose UPC carries a percent-off rule. Sits in the same "deal accent"
     * family as {@link #PROMO} and {@link #PROMO_FIXED}, all three shown in the grid's colour legend.
     * A brighter, greener blue than the deep navy {@link #CARD_DEBIT} tender so the two don't blur.
     */
    public static final Color PROMO_PERCENT = new Color(0x1C, 0x7E, 0xD6);
    /**
     * Teal — a Quick Add tile whose UPC carries a flat amount-off rule (including "Buy N Save $X").
     * The third member of the promo-accent family, distinct from the azure {@link #PROMO_PERCENT} and
     * the violet {@link #PROMO}, and clear of {@link #GO} green.
     */
    public static final Color PROMO_FIXED = new Color(0x0E, 0x8A, 0x7D);
    /** Tint used on selected basket rows and change-due strip. */
    public static final Color SELECTED = new Color(0xEC, 0xF3, 0xF0);
    /** Row hover background — one step darker than SURFACE, still lighter than SELECTED. */
    public static final Color HOVER_ROW = new Color(0xF5, 0xF6, 0xF3);
    /** Muted rule inside the basket list between rows. */
    public static final Color ROW_RULE = new Color(0xF1, 0xEF, 0xEA);
    /** Fill for disabled controls. */
    public static final Color DISABLED_BG = new Color(0xF0, 0xEF, 0xEB);
    /** Foreground for disabled control text. */
    public static final Color DISABLED_FG = new Color(0xA8, 0xAB, 0xAF);

    // ---- Tender palette ---------------------------------------------------
    // Each tender type carries its own fill so a cashier can hit the right button by colour
    // without reading the label. Green for cash (the most common tender in a convenience store,
    // and the pay-forward colour the rest of the system already speaks in), deep blue for debit,
    // indigo for credit — separated at the hue level so they read cleanly even at a glance
    // through fluorescent glare. All three exceed WCAG 4.5:1 contrast against white text.

    /** Deep blue for {@link com.rocketpartners.onboarding.commons.model.TenderType#DEBIT}. */
    public static final Color CARD_DEBIT = new Color(0x1E, 0x40, 0xAF);
    /** Indigo for {@link com.rocketpartners.onboarding.commons.model.TenderType#CREDIT}. Kept a
     *  full step off {@link #CARD_DEBIT} in hue so debit and credit don't blur under glare. */
    public static final Color CARD_CREDIT = new Color(0x6D, 0x28, 0xD9);

    // ---- Button elevation tokens ------------------------------------------
    // The resting state of every {@link PosButton} composes shadow + fill + lip + top-highlight
    // in a fixed order. The paint code reads these tokens rather than computing shades ad hoc,
    // so a change to the elevation vocabulary — softer shadow, taller lip — is one edit here
    // rather than five across variants.

    /** Vertical offset of the drop shadow below the body fill. Fixed; do not animate. */
    public static final int BUTTON_SHADOW_OFFSET = 2;
    /** Alpha of the tighter inner drop-shadow stamp, layered closest to the fill. */
    public static final int BUTTON_SHADOW_ALPHA_INNER = 10;
    /** Alpha of the softer outer drop-shadow stamp, one pixel further out than the inner. */
    public static final int BUTTON_SHADOW_ALPHA_OUTER = 6;
    /** Thickness of the bottom lip band, in pixels. The lip is what reads as physical depth. */
    public static final int BUTTON_LIP_HEIGHT = 3;
    /** Multiplier applied to the base fill to derive the lip colour: ~12% darker. */
    public static final float BUTTON_LIP_SHADE = 0.88f;
    /** Multiplier applied to the base fill to derive the 1px solid border colour: ~14% darker.
     *  A hair darker than {@link #BUTTON_LIP_SHADE} so the border reads as the edge of the button
     *  itself rather than a frame laid on top — a neutral-grey outline around a coloured fill looks
     *  like a separate rectangle, a darker shade of the fill looks like the object's own edge.
     *  Precomputed once per button (see {@code PosButton}); no call site does its own arithmetic. */
    public static final float BUTTON_BORDER_SHADE = 0.86f;
    /** Alpha of the 1px inside-top-edge highlight painted only on dark-fill buttons. */
    public static final int BUTTON_TOP_HIGHLIGHT_ALPHA = 38;
    /** Corner radius of every rounded button rect. */
    public static final int BUTTON_CORNER_RADIUS = 10;
    /** Touch-target minimum height for primary and tender buttons. */
    public static final int BUTTON_HEIGHT_PRIMARY = 48;
    /** Touch-target minimum height for secondary and danger (dialog) buttons. */
    public static final int BUTTON_HEIGHT_SECONDARY = 44;
    /** Minimum horizontal/vertical gap between adjacent tap targets. */
    public static final int BUTTON_GAP = 8;
    /** Luminance below which a button counts as "dark" and paints a top highlight. */
    public static final int BUTTON_DARK_LUMINANCE = 140;

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

    // ---- Promo accent mapping ---------------------------------------------

    /**
     * The accent colour for a promotional discount's kind, shared by the Quick Add tile edge, the
     * grid's colour legend, and the basket's per-item discount / free rows so one deal reads as one
     * colour everywhere: percent-off {@link #PROMO_PERCENT} azure, buy-N-get-M {@link #PROMO} violet,
     * amount-off {@link #PROMO_FIXED} teal.
     *
     * @param type the discount type; must not be {@code null}
     * @return the theme token for that type
     */
    public static Color promoAccent(com.rocketpartners.onboarding.commons.model.DiscountType type) {
        return switch (type) {
            case PERCENT_OFF -> PROMO_PERCENT;
            case FIXED_AMOUNT_OFF -> PROMO_FIXED;
            case PROMO -> PROMO;
        };
    }

    // ---- Colour helpers ---------------------------------------------------

    /**
     * Multiplies each RGB channel of {@code c} by {@code factor}, clamped to {@code [0, 255]}.
     * Alpha is preserved. Kept package-private and static so button constructors can precompute
     * their lip/pressed shades once and cache them as fields.
     */
    static Color shade(Color c, float factor) {
        return new Color(
                Math.min(255, Math.max(0, Math.round(c.getRed() * factor))),
                Math.min(255, Math.max(0, Math.round(c.getGreen() * factor))),
                Math.min(255, Math.max(0, Math.round(c.getBlue() * factor))),
                c.getAlpha());
    }

    /**
     * @return true if the base fill is dark enough that a translucent-white top-edge highlight
     *         will register. Applied to primary and tender buttons; secondary/danger tints are
     *         too pale for the highlight to read and it would just look like a paint smear.
     */
    static boolean isDarkFill(Color c) {
        int luminance = (int) (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue());
        return luminance < BUTTON_DARK_LUMINANCE;
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
