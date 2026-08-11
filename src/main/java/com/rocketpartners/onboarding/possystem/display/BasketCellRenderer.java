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
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;

/**
 * Renders one basket row as a dense four-column table row: <strong>Item · Price · Qty ·
 * Total</strong>. The description is left-aligned and ellipsised into whatever width remains
 * after the fixed numeric columns (≈235px in the shipping left-column width); Price, Qty, and
 * Total are right-aligned. Values render in {@link PosTheme#BODY}; the column headers painted
 * above the list (see {@code CustomerView}) are the matching {@code EYEBROW} labels, aligned to
 * the same column geometry exposed by {@link #numericColumns(JComponent, JComponent, JComponent)}
 * and {@link #ITEM_INSET_LEFT}.
 *
 * <p><strong>Qty is the badge.</strong> The quantity column is the {@link BadgePanel} pill: a
 * multi-quantity line jumps out of the list — a wrong quantity is the expensive mistake — while a
 * quantity of one reads as blank, the same convenience-store convention the previous design used.
 * Keeping the badge as the qty indicator also means the density regression tests, which probe the
 * badge's paint behaviour, keep describing a real element rather than a vestige.</p>
 *
 * <p>Two density modes change only the row height, not the columns:</p>
 * <ul>
 *   <li><strong>Comfortable</strong> (≤ {@value #DENSITY_THRESHOLD} items, {@value
 *       #COMFORTABLE_ROW_HEIGHT}px rows).</li>
 *   <li><strong>Compact</strong> (&gt; {@value #DENSITY_THRESHOLD} items, {@value
 *       #COMPACT_ROW_HEIGHT}px rows).</li>
 * </ul>
 *
 * <p>Row states, in precedence order (highest first): <em>flash</em> (a green tint painted by the
 * container at row bounds; the renderer only pulses the badge in sympathy), <em>selected</em>
 * ({@link PosTheme#SELECTED}), <em>hover</em> ({@link PosTheme#HOVER_ROW}). Voided lines are struck
 * through and muted but stay visible — a void is not a delete.</p>
 *
 * <p>All components, fonts, and colours are allocated once in the constructor and mutated per
 * call; {@link #getListCellRendererComponent} allocates no {@code Component}, {@code Font},
 * {@code Color}, or {@code Border}, which is what keeps a 250-item list smooth. JList virtualizes
 * to visible rows at a fixed cell height, so no further caching is needed.</p>
 */
public class BasketCellRenderer extends JPanel implements ListCellRenderer<LineItem> {

    /** Row height when the basket has few enough items to breathe. */
    public static final int COMFORTABLE_ROW_HEIGHT = 52;
    /** Row height when the basket is dense and every pixel counts. */
    public static final int COMPACT_ROW_HEIGHT = 42;
    /** The threshold at which the list switches from Comfortable to Compact. */
    public static final int DENSITY_THRESHOLD = 9;

    // ---- Column geometry (shared with the header row in CustomerView) ------
    /** Left inset of the Item column — the header's "Item" label uses the same inset. */
    public static final int ITEM_INSET_LEFT = 12;
    /** Right inset past the Total column. */
    public static final int ITEM_INSET_RIGHT = 12;
    /** Fixed width of the Price column. */
    public static final int PRICE_COL_WIDTH = 66;
    /** Fixed width of the Qty column (holds the {@link BadgePanel}). */
    public static final int QTY_COL_WIDTH = 40;
    /** Fixed width of the Total column. */
    public static final int TOTAL_COL_WIDTH = 66;
    /** Gap between adjacent numeric columns. */
    public static final int COL_GAP = 6;

    private final JLabel description = new JLabel();
    private final JLabel price = new JLabel("", SwingConstants.RIGHT);
    private final JLabel extended = new JLabel("", SwingConstants.RIGHT);
    private final BadgePanel badge = new BadgePanel();

    private final Font valueFont = PosTheme.base(Font.PLAIN, PosTheme.BODY);
    private final Font totalFont = PosTheme.base(Font.BOLD, PosTheme.BODY);

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

    public BasketCellRenderer() {
        super(new BorderLayout(COL_GAP, 0));
        // Symmetric vertical inset keeps content centred in both densities — only the list's
        // fixed cell height changes between modes. A hairline rule separates rows.
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, PosTheme.ROW_RULE),
                BorderFactory.createEmptyBorder(6, ITEM_INSET_LEFT, 6, ITEM_INSET_RIGHT)));

        description.setFont(valueFont);
        price.setFont(valueFont);
        price.setForeground(PosTheme.MUTED);
        extended.setFont(totalFont);

        add(description, BorderLayout.CENTER);
        add(numericColumns(price, badge, extended), BorderLayout.EAST);
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

        boolean voided = value.isVoided();
        boolean hovered = index == hoverIndex;

        // Flash > selection > hover > default. Flash is painted as an overlay on top of the
        // resolved background (see CustomerView.FlashLayerPanel), so here we resolve against
        // selection/hover only; the overlay layers green on top.
        Color bg = isSelected ? PosTheme.SELECTED
                : hovered ? PosTheme.HOVER_ROW
                : PosTheme.SURFACE;
        setBackground(bg);

        int qty = value.getQuantity();
        badge.setQuantity(qty, voided, index == flashIndex && flashIsBump);

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

    /**
     * A compact pill/circle drawn in the Qty column. Hidden when quantity is 1 while its width
     * stays reserved — Square/Shopify do this so a multi-quantity line jumps out of the list,
     * which is the actual goal since a wrong quantity is the expensive mistake.
     */
    static final class BadgePanel extends JPanel {
        static final int WIDTH = 34;
        static final int HEIGHT = 22;

        private int quantity = 1;
        private boolean voided;
        private boolean pulsing;

        BadgePanel() {
            setOpaque(false);
            setPreferredSize(new Dimension(WIDTH, HEIGHT));
            setMinimumSize(new Dimension(WIDTH, HEIGHT));
        }

        void setQuantity(int quantity, boolean voided, boolean pulsing) {
            this.quantity = quantity;
            this.voided = voided;
            this.pulsing = pulsing;
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (quantity <= 1) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = Math.min(WIDTH, getWidth());
            int h = Math.min(HEIGHT, getHeight());
            int x = (getWidth() - w) / 2;
            int y = (getHeight() - h) / 2;

            Color fill = voided ? PosTheme.DISABLED_BG
                    : pulsing ? PosTheme.LIVE
                    : PosTheme.BADGE_BG;
            g2.setColor(fill);
            g2.fillRoundRect(x, y, w, h, h, h);

            String text = String.valueOf(quantity);
            g2.setFont(getFont() != null
                    ? getFont().deriveFont(Font.BOLD, 12f)
                    : new Font(Font.SANS_SERIF, Font.BOLD, 12));
            FontMetrics fm = g2.getFontMetrics();
            int tx = x + (w - fm.stringWidth(text)) / 2;
            int ty = y + (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.setColor(voided ? PosTheme.DISABLED_FG : PosTheme.BADGE_FG);
            g2.drawString(text, tx, ty);
            g2.dispose();
        }
    }
}
