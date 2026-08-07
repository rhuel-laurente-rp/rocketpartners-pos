package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.LineItem;

import javax.swing.BorderFactory;
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
 * Renders one basket row in one of two density modes.
 *
 * <p><strong>Comfortable</strong> (≤ 10 items, ~52px rows). Two lines: description, then a muted
 * {@code @ $unit} line beneath. Reads calmly when the basket is short.</p>
 *
 * <p><strong>Compact</strong> (&gt; 10 items, ~42px rows). One line: description on the left,
 * {@code @ $unit} and extended total right-aligned. Font sizes are identical to Comfortable —
 * only the row height and vertical stacking change.</p>
 *
 * <p>Row states, in precedence order (highest first):</p>
 * <ol>
 *   <li><em>Flash</em> — a green tint painted by the container at row bounds; renderers only
 *       need to know the flash is happening so the badge can pulse in sympathy.</li>
 *   <li><em>Selected</em> — {@link PosTheme#SELECTED} background.</li>
 *   <li><em>Hover</em> — {@link PosTheme#HOVER_ROW} background (tracked by the view via
 *       MouseMotionListener since {@link JList} has no hover concept).</li>
 * </ol>
 *
 * <p>Voided lines are struck through and muted but stay visible — the cashier and the customer
 * both need to see that a void happened, so this is not a delete.</p>
 *
 * <p>All fields, fonts, and colours are allocated once in the constructor and mutated per call.
 * No new components, Font, Color, or Border objects are allocated inside
 * {@link #getListCellRendererComponent} — that is what keeps a 250-item list feeling smooth even
 * on modest hardware. JList already virtualizes to visible rows with a fixed cell height, so no
 * additional caching is needed.</p>
 */
public class BasketCellRenderer extends JPanel implements ListCellRenderer<LineItem> {

    /** Row height when the basket has few enough items to breathe. */
    public static final int COMFORTABLE_ROW_HEIGHT = 52;
    /** Row height when the basket is dense and every pixel counts. */
    public static final int COMPACT_ROW_HEIGHT = 42;
    /** The threshold at which the list switches from Comfortable to Compact. */
    public static final int DENSITY_THRESHOLD = 10;

    private final JLabel description = new JLabel();
    private final JLabel unitPrice = new JLabel();
    private final JLabel extended = new JLabel("", SwingConstants.RIGHT);
    private final BadgePanel badge = new BadgePanel();
    private final JPanel textStack = new JPanel(new BorderLayout());
    private final JPanel comfortableText = new JPanel(new BorderLayout());
    private final JPanel compactText = new JPanel(new BorderLayout(8, 0));

    private final Font descFontRow = PosTheme.base(Font.PLAIN, PosTheme.ROW);
    private final Font descFontBold = PosTheme.base(Font.BOLD, PosTheme.ROW);
    private final Font unitFont = PosTheme.base(Font.PLAIN, 12f);
    private final Font extendedFont = PosTheme.base(Font.BOLD, PosTheme.BUTTON);

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
        super(new BorderLayout(12, 0));
        // Border padding is symmetric so the swap between Comfortable and Compact only requires
        // changing the list's fixed cell height; contents stay centred vertically in both modes.
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, PosTheme.ROW_RULE),
                BorderFactory.createEmptyBorder(6, 14, 6, 14)));

        JPanel badgeWrap = new JPanel(new GridBagLayout());
        badgeWrap.setOpaque(false);
        badgeWrap.add(badge);
        add(badgeWrap, BorderLayout.WEST);

        description.setFont(descFontRow);
        unitPrice.setFont(unitFont);
        unitPrice.setForeground(PosTheme.MUTED);

        comfortableText.setOpaque(false);
        comfortableText.add(description, BorderLayout.NORTH);
        comfortableText.add(unitPrice, BorderLayout.CENTER);

        compactText.setOpaque(false);

        textStack.setOpaque(false);
        add(textStack, BorderLayout.CENTER);

        extended.setFont(extendedFont);
        add(extended, BorderLayout.EAST);
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

    @Override
    public Component getListCellRendererComponent(
            JList<? extends LineItem> list, LineItem value, int index,
            boolean isSelected, boolean cellHasFocus) {

        boolean voided = value.isVoided();
        boolean hovered = index == hoverIndex;

        // Flash > selection > hover > default. Flash is painted as an overlay on top of the
        // resolved background (see CustomerView.FlashOverlay), so here we still resolve
        // background against selection/hover; the overlay layers green on top.
        Color bg = isSelected ? PosTheme.SELECTED
                : hovered ? PosTheme.HOVER_ROW
                : PosTheme.SURFACE;
        setBackground(bg);

        int qty = value.getQuantity();
        badge.setQuantity(qty, voided,
                index == flashIndex && flashIsBump);

        String label = value.getItem().getDisplayLabel().trim();
        // Voided rows use HTML so the strike is a real strike; non-voided stays plain to keep
        // rendering as cheap as possible. Bold description in Compact mode helps it hold its
        // weight now that the second line is gone.
        if (voided) {
            description.setText("<html><strike>" + escapeHtml(label)
                    + "</strike> &nbsp;<font color='#A32A1F'>VOID</font></html>");
        } else {
            description.setText(label);
        }
        description.setFont(density == Density.COMPACT ? descFontBold : descFontRow);
        description.setForeground(voided ? PosTheme.DISABLED_FG : PosTheme.INK);

        unitPrice.setText("@ " + PosTheme.money(value.getItem().getUnitPrice()));
        unitPrice.setForeground(voided ? PosTheme.DISABLED_FG : PosTheme.MUTED);

        extended.setText(PosTheme.money(value.extendedTotal()));
        extended.setForeground(voided ? PosTheme.DISABLED_FG : PosTheme.INK);

        // Swap the middle stack to match density. Both containers and their children are
        // preallocated; only the parent-child edge changes, so there's no allocation on the
        // render path. Description/unit-price get re-parented between comfortableText and
        // compactText — Swing tolerates this since we removeAll() first.
        textStack.removeAll();
        comfortableText.removeAll();
        compactText.removeAll();
        if (density == Density.COMPACT) {
            compactText.add(description, BorderLayout.CENTER);
            compactText.add(unitPrice, BorderLayout.EAST);
            textStack.add(compactText, BorderLayout.CENTER);
        } else {
            comfortableText.add(description, BorderLayout.NORTH);
            comfortableText.add(unitPrice, BorderLayout.CENTER);
            textStack.add(comfortableText, BorderLayout.CENTER);
        }
        return this;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * A compact pill/circle drawn beside the description. Hidden when quantity is 1 while its
     * width stays reserved — Square/Shopify do this so a multi-quantity line jumps out of the
     * list, which is the actual goal since a wrong quantity is the expensive mistake.
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
