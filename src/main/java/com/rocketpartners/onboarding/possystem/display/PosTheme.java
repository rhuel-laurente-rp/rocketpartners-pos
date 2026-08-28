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
 *
 * <p><b>Light and dark.</b> Every colour token resolves its value once, at class-load, from the
 * {@code pos.theme} system property ({@code LIGHT} — the default — or {@code DARK}). {@code
 * Application} sets that property from the {@code --theme} CLI flag before any display class is
 * loaded, and the matching FlatLaf variant is installed alongside it. Because the choice is made
 * before a single component is built, every view simply reads the already-selected palette when
 * it is constructed — there is no runtime toggle and no component-tree rebuild. Token <i>names</i>
 * and their light values are unchanged from the light-only design, so consumers and tests that
 * compare against a token move in lockstep with it.</p>
 */
public final class PosTheme {

    private PosTheme() {}

    // ---- Theme mode --------------------------------------------------------
    // Resolved once, at class-load, from the pos.theme system property. Declared before any
    // colour field so it is set before the first pick(...) runs (static fields initialise in
    // textual order). Application sets the property from --theme before any display class loads.

    private enum Mode { LIGHT, DARK }

    private static final Mode MODE =
            "DARK".equalsIgnoreCase(System.getProperty("pos.theme", "LIGHT")) ? Mode.DARK : Mode.LIGHT;

    /** @return true when the dark palette is active. Handy for snapshot tools and opt-in tests. */
    public static boolean isDark() {
        return MODE == Mode.DARK;
    }

    /**
     * Selects a token's value for the active mode. Each argument is a packed {@code 0xRRGGBB}
     * literal; the returned {@link Color} is fully opaque. In light mode the light value is
     * byte-identical to the pre-dark-mode design.
     */
    private static Color pick(int lightRgb, int darkRgb) {
        return new Color(MODE == Mode.DARK ? darkRgb : lightRgb);
    }

    // ---- Palette -----------------------------------------------------------

    /** Near-black chrome strip: header bar and dialog header. Stays dark in both modes. */
    public static final Color INK = pick(0x14181D, 0x0E1116);
    /** Primary text and labels on {@link #PAPER}/{@link #SURFACE}. Near-white in dark mode. */
    public static final Color TEXT_PRIMARY = pick(0x14181D, 0xE7EAEE);
    /** Warm off-white for the app-wide background. Reads as receipt tape, not screen white. */
    public static final Color PAPER = pick(0xFBFAF7, 0x14171C);
    /** Card and dialog body surfaces. Pure white in light mode, elevated graphite in dark. */
    public static final Color SURFACE = pick(0xFFFFFF, 0x1E232B);
    /** Hairline colour used for card borders, summary rules, and disabled outlines. */
    public static final Color RULE = pick(0xE2E0DA, 0x363D47);
    /** Secondary label colour: metadata, unit prices, disabled hints. */
    public static final Color MUTED = pick(0x6E7379, 0x9AA0A7);
    /** Saturated green reserved for pay-forward affirmative actions. Brightened for dark. */
    public static final Color GO = pick(0x0B6E4F, 0x1DA46E);
    /** Saturated red for destructive actions (void basket) and error accents. */
    public static final Color STOP = pick(0xA32A1F, 0xE5645A);
    /** Amber for "live / awaiting" states (tender enabled, processing card). */
    public static final Color LIVE = pick(0xC97A0E, 0xCC8A1F);
    /**
     * Violet — the buy-N-get-M "free item" / promo marker, on a basket free-row and on a Quick Add
     * tile whose UPC carries a {@code PROMO} rule. Deliberately none of the hues already carrying
     * meaning: {@link #GO} green is the pay-forward / hover / selection / newest-scan-flash colour (a
     * green promo tag would blend into those transient row states), {@link #LIVE} amber is the
     * processing/awaiting state, {@link #STOP} red is void/error, and blue/indigo
     * ({@link #CARD_DEBIT}/{@link #CARD_CREDIT}) are the tenders. Violet reads as a persistent "deal"
     * accent that a cashier won't confuse with any of those.
     */
    public static final Color PROMO = pick(0x9D2EA8, 0xC264D0);
    /**
     * Azure — a Quick Add tile whose UPC carries a percent-off rule. Sits in the same "deal accent"
     * family as {@link #PROMO} and {@link #PROMO_FIXED}, all three shown in the grid's colour legend.
     * A brighter, greener blue than the deep navy {@link #CARD_DEBIT} tender so the two don't blur.
     */
    public static final Color PROMO_PERCENT = pick(0x1C7ED6, 0x43A6F5);
    /**
     * Teal — a Quick Add tile whose UPC carries a flat amount-off rule (including "Buy N Save $X").
     * The third member of the promo-accent family, distinct from the azure {@link #PROMO_PERCENT} and
     * the violet {@link #PROMO}, and clear of {@link #GO} green.
     */
    public static final Color PROMO_FIXED = pick(0x0E8A7D, 0x1FB5A6);
    /** Tint used on selected basket rows and change-due strip. */
    public static final Color SELECTED = pick(0xECF3F0, 0x1C2E27);
    /** Row hover background — one step darker than SURFACE, still lighter than SELECTED. */
    public static final Color HOVER_ROW = pick(0xF5F6F3, 0x262B33);
    /** Muted rule inside the basket list between rows. */
    public static final Color ROW_RULE = pick(0xF1EFEA, 0x2A3039);
    /** Fill for disabled controls. */
    public static final Color DISABLED_BG = pick(0xF0EFEB, 0x24292F);
    /** Foreground for disabled control text. */
    public static final Color DISABLED_FG = pick(0xA8ABAF, 0x5C6169);

    // ---- Tender palette ---------------------------------------------------
    // Each tender type carries its own fill so a cashier can hit the right button by colour
    // without reading the label. Green for cash (the most common tender in a convenience store,
    // and the pay-forward colour the rest of the system already speaks in), deep blue for debit,
    // indigo for credit — separated at the hue level so they read cleanly even at a glance
    // through fluorescent glare. All three exceed WCAG 4.5:1 contrast against white text, and
    // stay mutually distinct (and distinct from DISABLED_BG) in both light and dark palettes.

    /** Deep blue for {@link com.rocketpartners.onboarding.commons.model.TenderType#DEBIT}. */
    public static final Color CARD_DEBIT = pick(0x1E40AF, 0x3B6FE0);
    /** Indigo for {@link com.rocketpartners.onboarding.commons.model.TenderType#CREDIT}. Kept a
     *  full step off {@link #CARD_DEBIT} in hue so debit and credit don't blur under glare. */
    public static final Color CARD_CREDIT = pick(0x6D28D9, 0x8B5CF6);

    // ---- Component fills ---------------------------------------------------
    // Tints and chrome shades that used to live as literals in individual views. Kept here so the
    // "no view hard-codes a colour" rule holds and every surface tracks the active palette.

    /** Pale neutral fill for secondary (non-affirmative) dialog buttons. */
    public static final Color BUTTON_SECONDARY_FILL = pick(0xF2F1ED, 0x2E343D);
    /** Pale {@link #STOP} tint for danger buttons and the error dialog accent. */
    public static final Color BUTTON_DANGER_FILL = pick(0xFDF1EF, 0x3A2320);
    /** Pale {@link #GO} tint for affirmative-secondary buttons. */
    public static final Color BUTTON_GO_TINT_FILL = pick(0xE8F4EE, 0x17302A);
    /** Scrollbar thumb colour, sitting one step off {@link #SURFACE} in both modes. */
    public static final Color SCROLL_THUMB = pick(0xC7C5BF, 0x3C434D);
    /** Pale text painted on the dark {@link #INK} header strip (journal pill, status chips). */
    public static final Color HEADER_TEXT = pick(0xC9D1D8, 0xC9D1D8);
    /** Background of the login splash / vector panel: brand {@link #GO} green in light, a deep
     *  green in dark so the transparent-cornered illustration still reads against a dark field. */
    public static final Color LOGIN_VECTOR_BG = pick(0x0B6E4F, 0x0B3D2E);

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
