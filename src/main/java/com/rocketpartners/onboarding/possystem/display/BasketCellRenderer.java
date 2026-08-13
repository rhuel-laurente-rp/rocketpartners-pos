package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.LineItem;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagLayout;

/**
 * Renders one basket row as a dense four-column table row: <strong>Item · Price · Qty ·
 * Total</strong>. The description is left-aligned and ellipsised into whatever width remains
 * after the fixed numeric columns (≈235px in the shipping left-column width); Price, Qty, and
 * Total are right-aligned. Values render in {@link PosTheme#BODY}; the column headers painted
 * above the list (see {@code CustomerView}) are the matching {@code EYEBROW} labels, aligned to
 * the same column geometry exposed by {@link #numericColumns(JComponent, JComponent, JComponent)}
 * and {@link #ITEM_INSET_LEFT}.
 *
 * <p><strong>Qty is plain text.</strong> The quantity column renders the number directly — no
 * pill, no ellipse. In a four-column table the column itself is the distinction, so a badge would
 * be redundant chrome. Every line shows its count, including {@code 1}: an empty cell in a table
 * column reads as missing data, whereas a badge could sensibly stay blank at one.</p>
 *
 * <p><strong>Density is padding, not font.</strong> Two modes change the row's vertical padding
 * (and therefore its height) while the type stays put at {@link PosTheme#BODY} — legible at
 * counter distance under fluorescent light, where a smaller face starts getting price digits
 * misread. Comfortable breathes; compact tightens the padding so more rows fit once the basket
 * grows:</p>
 * <ul>
 *   <li><strong>Comfortable</strong> (≤ {@value #DENSITY_THRESHOLD} items, {@value
 *       #COMFORTABLE_ROW_PAD}px vertical padding, {@value #COMFORTABLE_ROW_HEIGHT}px rows).</li>
 *   <li><strong>Compact</strong> (&gt; {@value #DENSITY_THRESHOLD} items, {@value
 *       #COMPACT_ROW_PAD}px vertical padding, {@value #COMPACT_ROW_HEIGHT}px rows).</li>
 * </ul>
 *
 * <p>Row states, in precedence order (highest first): <em>flash</em> (a green tint painted by the
 * container at row bounds), <em>selected</em> ({@link PosTheme#SELECTED}), <em>hover</em>
 * ({@link PosTheme#HOVER_ROW}). Voided lines are struck through and muted but stay visible — a
 * void is not a delete.</p>
 *
 * <p>All components, fonts, and colours are allocated once in the constructor and mutated per
 * call; {@link #getListCellRendererComponent} allocates no {@code Component}, {@code Font},
 * {@code Color}, or {@code Border}, which is what keeps a 250-item list smooth. JList virtualizes
 * to visible rows at a fixed cell height, so no further caching is needed.</p>
 */
public class BasketCellRenderer extends JPanel implements ListCellRenderer<LineItem> {

    /** Vertical padding above and below the row content when the basket can breathe. */
    public static final int COMFORTABLE_ROW_PAD = 6;
    /** Vertical padding once the basket is dense — tightened, but the type is unchanged. */
    public static final int COMPACT_ROW_PAD = 2;
    /** Row height when the basket has few enough items to breathe (BODY line height + padding). */
    public static final int COMFORTABLE_ROW_HEIGHT = 30;
    /** Row height when the basket is dense and every pixel counts. */
    public static final int COMPACT_ROW_HEIGHT = 22;
    /** The threshold at which the list switches from Comfortable to Compact. */
    public static final int DENSITY_THRESHOLD = 18;

    // ---- Column geometry (shared with the header row in CustomerView) ------
    /** Left inset of the Item column — the header's "Item" label uses the same inset. */
    public static final int ITEM_INSET_LEFT = 12;
    /** Right inset past the Total column. */
    public static final int ITEM_INSET_RIGHT = 12;
    /** Fixed width of the Price column. */
    public static final int PRICE_COL_WIDTH = 66;
    /** Fixed width of the Qty column (holds the plain-text quantity). */
    public static final int QTY_COL_WIDTH = 40;
    /** Fixed width of the Total column. */
    public static final int TOTAL_COL_WIDTH = 66;
    /** Gap between adjacent numeric columns. */
    public static final int COL_GAP = 6;

    private final JLabel description = new JLabel();
    private final JLabel price = new JLabel("", SwingConstants.RIGHT);
    private final JLabel extended = new JLabel("", SwingConstants.RIGHT);
    private final JLabel qty = new JLabel("", SwingConstants.CENTER);

    private final Font valueFont = PosTheme.base(Font.PLAIN, PosTheme.BODY);
    private final Font totalFont = PosTheme.base(Font.BOLD, PosTheme.BODY);

    // Two prebuilt borders — one per density. Precomputed so a density switch is a field swap,
    // not an allocation, and getListCellRendererComponent never touches border construction.
    private final javax.swing.border.Border comfortableBorder = rowBorder(COMFORTABLE_ROW_PAD);
    private final javax.swing.border.Border compactBorder = rowBorder(COMPACT_ROW_PAD);

    private Density density = Density.COMFORTABLE;

    /**
     * Index of the row currently being highlighted by the newest-scan flash, or {@code -1}
     * when no flash is running. The container drives this — the renderer just knows to punch
     * the badge into a pulse colour so a merged scan is visibly distinct from nothing.
     */
    private int flashIndex = -1;
    /** {@code true} when the flash row is a quantity bump (as opposed to a fresh add). */
    private boolean flashIsBump;

    /** Index of the currently hovered row, or {@code -1} for none. */
    private int hoverIndex = -1;

    /** Pre-rendered hex for {@link PosTheme#PROMO}, so the free-row HTML costs no per-row allocation. */
    private final String promoHex = hex(PosTheme.PROMO);

    public BasketCellRenderer() {
        super(new BorderLayout(COL_GAP, 0));
        // The vertical inset IS the density knob — comfortable padding by default, tightened in
        // compact mode — while the type stays fixed. A hairline rule separates rows.
        setBorder(comfortableBorder);

        description.setFont(valueFont);
        price.setFont(valueFont);
        price.setForeground(PosTheme.MUTED);
        qty.setFont(valueFont);
        extended.setFont(totalFont);

        add(description, BorderLayout.CENTER);
        add(numericColumns(price, qty, extended), BorderLayout.EAST);
    }

    /** A row border: the shared bottom hairline plus the density-specific vertical padding. */
    private static javax.swing.border.Border rowBorder(int verticalPad) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, PosTheme.ROW_RULE),
                BorderFactory.createEmptyBorder(verticalPad, ITEM_INSET_LEFT,
                        verticalPad, ITEM_INSET_RIGHT));
    }

    /**
     * Builds the right-hand numeric column group — Price, Qty, Total — at the shared fixed
     * widths, each cell right-aligning its content. Used by this renderer for data rows and by
     * {@code CustomerView} for the column-header row so the two align pixel-for-pixel.
     *
     * @param priceCell content for the Price column (right-aligned within its cell)
     * @param qtyCell   content for the Qty column (centred within its cell)
     * @param totalCell content for the Total column (right-aligned within its cell)
     */
    public static JPanel numericColumns(JComponent priceCell, JComponent qtyCell, JComponent totalCell) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.add(fixedCell(priceCell, PRICE_COL_WIDTH, false));
        row.add(Box.createHorizontalStrut(COL_GAP));
        row.add(fixedCell(qtyCell, QTY_COL_WIDTH, true));
        row.add(Box.createHorizontalStrut(COL_GAP));
        row.add(fixedCell(totalCell, TOTAL_COL_WIDTH, false));
        return row;
    }

    private static JPanel fixedCell(JComponent content, int width, boolean centre) {
        // GridBagLayout centres a single child vertically; for a plain label that just holds the
        // baseline while the label's own RIGHT alignment handles the horizontal edge. For the
        // badge (centre=true) the centring is both axes, which reads right in a narrow column.
        JPanel cell = new JPanel(centre ? new GridBagLayout() : new BorderLayout());
        cell.setOpaque(false);
        cell.add(content, centre ? null : BorderLayout.CENTER);
        Dimension fixed = new Dimension(width, 1);
        cell.setPreferredSize(new Dimension(width, cell.getPreferredSize().height));
        cell.setMinimumSize(fixed);
        cell.setMaximumSize(new Dimension(width, Integer.MAX_VALUE));
        return cell;
    }

    /** Density mode. */
    public enum Density { COMFORTABLE, COMPACT }

    /**
     * Chooses the density from the current item count. Kept static so callers can decide
     * whether to trigger the animated transition without instantiating a renderer.
     */
    public static Density densityFor(int itemCount) {
        return itemCount > DENSITY_THRESHOLD ? Density.COMPACT : Density.COMFORTABLE;
    }

    public void setDensity(Density density) {
        this.density = density;
        // The density switch changes padding, not font: swap the prebuilt border for the mode.
        setBorder(density == Density.COMPACT ? compactBorder : comfortableBorder);
    }

    public Density getDensity() {
        return density;
    }

    public void setFlashIndex(int index, boolean isQuantityBump) {
        this.flashIndex = index;
        this.flashIsBump = isQuantityBump;
    }

    public int getFlashIndex() {
        return flashIndex;
    }

    public void setHoverIndex(int index) {
        this.hoverIndex = index;
    }

    public int getHoverIndex() {
        return hoverIndex;
    }


    /**
     * Reports a modest preferred width so the {@link JList} tracks the viewport width rather than
     * growing to fit the widest description. Without this cap a long item name inflates the list's
     * preferred width past the viewport, and with the horizontal scrollbar disabled the right edge
     * — the Total column — is silently clipped, especially once the vertical scrollbar appears. The
     * real per-row width always comes from the list (viewport) width; the description ellipsises
     * into whatever the Item column has left.
     */
    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        d.width = PREFERRED_WIDTH_HINT;
        return d;
    }

    /** Small enough to stay under any real viewport width, so the list tracks the viewport. */
    private static final int PREFERRED_WIDTH_HINT = 240;

    @Override
    public Component getListCellRendererComponent(
            JList<? extends LineItem> list, LineItem value, int index,
            boolean isSelected, boolean cellHasFocus) {

        // A promotion's free units render as their own inert, indented row: PROMO-violet, no
        // hover/selection tint (it can't be selected), a "↳ … free" label, and a negative Total.
        // The price and qty columns are blank — it isn't a priced, countable product line.
        if (value instanceof FreeLineItem free) {
            setBackground(PosTheme.SURFACE);
            String label = free.getItem().getDisplayLabel().trim();
            description.setText("<html><font color='" + promoHex + "'>&nbsp;&nbsp;&nbsp;&#8627; "
                    + escapeHtml(label) + " — " + free.getFreeUnits() + " free</font></html>");
            description.setForeground(PosTheme.PROMO);
            price.setText("");
            qty.setText("");
            extended.setText("-" + PosTheme.money(free.getFreeAmount()));
            extended.setForeground(PosTheme.PROMO);
            return this;
        }

        boolean voided = value.isVoided();
        boolean hovered = index == hoverIndex;

        // Flash > selection > hover > default. Flash is painted as an overlay on top of the
        // resolved background (see CustomerView.FlashLayerPanel), so here we resolve against
        // selection/hover only; the overlay layers green on top.
        Color bg = isSelected ? PosTheme.SELECTED
                : hovered ? PosTheme.HOVER_ROW
                : PosTheme.SURFACE;
        setBackground(bg);

        // Plain-text quantity, always shown — including 1. The column is the distinction, so the
        // number needs no badge; a blank cell would read as missing data.
        qty.setText(String.valueOf(value.getQuantity()));
        qty.setForeground(voided ? PosTheme.DISABLED_FG : PosTheme.INK);

        String label = value.getItem().getDisplayLabel().trim();
        // Voided rows use HTML so the strike is a real strike; non-voided stays plain text so the
        // JList ellipsises the description automatically when the Item column is too narrow.
        if (voided) {
            description.setText("<html><strike>" + escapeHtml(label)
                    + "</strike> &nbsp;<font color='#A32A1F'>VOID</font></html>");
        } else {
            description.setText(label);
        }
        description.setForeground(voided ? PosTheme.DISABLED_FG : PosTheme.INK);

        price.setText(PosTheme.money(value.getItem().getUnitPrice()));
        price.setForeground(voided ? PosTheme.DISABLED_FG : PosTheme.MUTED);

        extended.setText(PosTheme.money(value.extendedTotal()));
        extended.setForeground(voided ? PosTheme.DISABLED_FG : PosTheme.INK);

        return this;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** {@code #RRGGBB} for a colour, for use inside an HTML {@code <font color>} attribute. */
    private static String hex(java.awt.Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    // ---- Test hooks --------------------------------------------------------

    /** For tests: the description column's rendered text (may be HTML) after the last render pass. */
    String getDescriptionTextForTest() {
        return description.getText();
    }

    /** For tests: the Total column's rendered text after the last render pass. */
    String getExtendedTextForTest() {
        return extended.getText();
    }

    /** For tests: the quantity column's rendered text after the last render pass. */
    String getQtyTextForTest() {
        return qty.getText();
    }

    /** For tests: the current vertical padding (top inset) — the density knob. */
    int getVerticalPaddingForTest() {
        return getInsets().top;
    }

    /** For tests: the value font, so a density test can assert the font is unchanged by a switch. */
    Font getValueFontForTest() {
        return valueFont;
    }
}
