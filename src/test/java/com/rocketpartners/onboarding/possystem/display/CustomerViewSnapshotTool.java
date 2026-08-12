package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual snapshot harness for the whole two-column {@link CustomerView} shell. Not a JUnit test —
 * it opens a real display — so it is invoked by hand (run this class directly from the IDE).
 *
 * <p>Renders the full window at its fixed 1512×982 in three states — an empty basket, three
 * items, and forty items (compact density) — and writes PNGs to {@code build/snapshots}. Useful
 * for eyeballing the proportional split, the basket table columns, and the Quick Add grid.
 * Skips silently in headless environments so it never bombs CI.</p>
 */
public final class CustomerViewSnapshotTool {

    private CustomerViewSnapshotTool() {}

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("headless environment — skipping snapshot render");
            return;
        }
        File out = new File("build/snapshots");
        if (!out.exists() && !out.mkdirs()) {
            throw new IllegalStateException("could not create " + out.getAbsolutePath());
        }
        snapshot(0, new File(out, "customerview-empty.png"));
        // Basket at 5 / 15 / 40 items brackets the density transition (threshold 9): 5 comfortable,
        // 15 and 40 compact, so the padding tightening is visible across the trio.
        snapshot(5, new File(out, "customerview-5.png"));
        snapshot(15, new File(out, "customerview-15.png"));
        snapshot(40, new File(out, "customerview-40.png"));
        snapshotWithKeyboard(new File(out, "quickadd-qwerty-open.png"));

        // Bottom strip standalone, both enable states, so the border/60-40/single-row work can be
        // judged without the rest of the window.
        snapshotBottomStrip(true, new File(out, "bottomstrip-enabled.png"));
        snapshotBottomStrip(false, new File(out, "bottomstrip-disabled.png"));

        // Quick Add footer on the first, a middle, and the last page — pager controls disabled at
        // the boundaries, the current-page pill and "N of M pages" indicator moving with the page.
        snapshotQuickAddFooter("first", new File(out, "quickadd-footer-first.png"));
        snapshotQuickAddFooter("middle", new File(out, "quickadd-footer-middle.png"));
        snapshotQuickAddFooter("last", new File(out, "quickadd-footer-last.png"));

        measure();

        System.out.println("Wrote full-window snapshots to " + out.getAbsolutePath());
        System.exit(0);
    }

    /**
     * Prints the measured width×height of every action and tender button, plus the visible basket
     * row counts at both densities. Uses forced layout at the true 1512×982 register surface (a
     * laptop screen would clamp a shown window shorter and understate the numbers).
     */
    static void measure() {
        CustomerView view = new CustomerView("Rocket POS — snapshot", quickAddItems(), noop());
        try {
            java.awt.Container content = view.getContentPane();
            content.setSize(1512, 982);
            view.updateBasket(basket(40), new BigDecimal("40.00"), BigDecimal.ZERO,
                    BigDecimal.ZERO, new BigDecimal("40.00"));
            layoutAll(content);

            PosButton[] actions = view.getActionButtonsForTest();
            String[] actionNames = {"Void Basket", "Void Line", "Change Qty", "Discount", "Total"};
            System.out.println("[measurement] Action buttons (60% of bottom row):");
            for (int i = 0; i < actions.length; i++) {
                System.out.println("  " + actionNames[i] + ": "
                        + actions[i].getWidth() + " x " + actions[i].getHeight() + " px");
            }
            PosButton[] tenders = view.getTenderButtonsForTest();
            String[] tenderNames = {"Pay Cash", "Pay Debit", "Pay Credit"};
            System.out.println("[measurement] Tender buttons (40% of bottom row):");
            for (int i = 0; i < tenders.length; i++) {
                System.out.println("  " + tenderNames[i] + ": "
                        + tenders[i].getWidth() + " x " + tenders[i].getHeight() + " px");
            }

            int viewportH = view.getBasketListForTest().getParent().getHeight();
            int comfyRows = viewportH / BasketCellRenderer.COMFORTABLE_ROW_HEIGHT;
            int compactRows = viewportH / BasketCellRenderer.COMPACT_ROW_HEIGHT;
            System.out.println("[measurement] Basket viewport height: " + viewportH + "px");
            System.out.println("  Comfortable (" + BasketCellRenderer.COMFORTABLE_ROW_HEIGHT
                    + "px rows): ~" + comfyRows + " visible rows");
            System.out.println("  Compact (" + BasketCellRenderer.COMPACT_ROW_HEIGHT
                    + "px rows): ~" + compactRows + " visible rows");
        } finally {
            view.dispose();
        }
    }

    /**
     * Renders the bottom strip (actions + payment) standalone in one enable state. Enabled selects
     * a basket row first so the selection-dependent actions (Change Qty / Void Line) light up too;
     * disabled locks basket input and tender, leaving Discount and the tenders dark.
     */
    static void snapshotBottomStrip(boolean enabled, File target) throws Exception {
        CustomerView view = new CustomerView("Rocket POS — snapshot", quickAddItems(), noop());
        try {
            java.awt.Container content = view.getContentPane();
            content.setSize(1512, 982);
            view.updateBasket(basket(3), new BigDecimal("3.00"), BigDecimal.ZERO,
                    BigDecimal.ZERO, new BigDecimal("3.00"));
            if (enabled) {
                view.setBasketInputEnabled(true);
                view.getBasketListForTest().setSelectedIndex(0);
                view.setTenderInputEnabled(true);
            } else {
                view.setBasketInputEnabled(false);
                view.setTenderInputEnabled(false);
            }
            layoutAll(content);

            java.awt.Container strip = view.getBottomRowForTest();
            BufferedImage img = new BufferedImage(Math.max(1, strip.getWidth()),
                    Math.max(1, strip.getHeight()), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setColor(PosTheme.PAPER);
                g.fillRect(0, 0, img.getWidth(), img.getHeight());
                strip.printAll(g);
            } finally {
                g.dispose();
            }
            ImageIO.write(img, "PNG", target);
            System.out.println("wrote " + target.getName());
        } finally {
            view.dispose();
        }
    }

    /**
     * Renders the Quick Add footer on the requested page ({@code first} / {@code middle} /
     * {@code last}) so the disabled-at-boundary pager controls and the current-page pill can be
     * eyeballed. A deterministic capacity is forced so the page count doesn't depend on the
     * laid-out grid size.
     */
    static void snapshotQuickAddFooter(String which, File target) throws Exception {
        CustomerView view = new CustomerView("Rocket POS — snapshot", quickAddItems(), noop());
        try {
            java.awt.Container content = view.getContentPane();
            content.setSize(1512, 982);
            QuickAddPanel qp = view.getQuickAddPanelForTest();
            // Lay out FIRST — the grid's resize listener recomputes capacity from the laid-out size,
            // which would clobber a capacity forced beforehand. The resize event is delivered
            // asynchronously on the EDT, so drain the queue before forcing capacity; otherwise a
            // late componentResized fires after we've set it and quietly resets the page count.
            layoutAll(content);
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
            qp.setCapacityForTest(4, 8); // 20 items → 3 pages
            switch (which) {
                case "first" -> qp.firstForTest();
                case "last" -> qp.lastForTest();
                default -> qp.nextForTest(); // middle
            }

            java.awt.Container footer = (java.awt.Container) qp.getComponent(qp.getComponentCount() - 1);
            BufferedImage img = new BufferedImage(Math.max(1, footer.getWidth()),
                    Math.max(1, footer.getHeight()), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setColor(PosTheme.SURFACE);
                g.fillRect(0, 0, img.getWidth(), img.getHeight());
                footer.printAll(g);
            } finally {
                g.dispose();
            }
            ImageIO.write(img, "PNG", target);
            System.out.println("wrote " + target.getName() + " (page "
                    + (qp.getPageForTest() + 1) + " of " + qp.getPageCountForTest() + ")");
        } finally {
            view.dispose();
        }
    }

    /**
     * Renders the full window with the Quick Add QWERTY open, and prints how many tile rows remain
     * visible above it versus with it closed — the grid shrinks into the space above rather than
     * being covered.
     */
    static void snapshotWithKeyboard(File target) throws Exception {
        // Measure against the true fixed 1512×982 register surface by forcing a full layout pass
        // rather than showing a real window — a laptop screen clamps a 982px window shorter, which
        // would understate the grid height. This mirrors DialogSnapshotTool's forced-layout idiom.
        CustomerView view = new CustomerView("Rocket POS — snapshot", quickAddItems(), noop());
        QuickAddPanel qp = view.getQuickAddPanelForTest();
        try {
            java.awt.Container content = view.getContentPane();
            content.setSize(1512, 982);
            layoutAll(content);
            qp.recomputeCapacityForTest();
            int colsClosed = Math.max(1, qp.getColumnsForTest());
            int rowsClosed = qp.getCapacityForTest() / colsClosed;

            qp.fireSearchFocusGainedForTest();
            layoutAll(content);
            qp.recomputeCapacityForTest();
            int colsOpen = Math.max(1, qp.getColumnsForTest());
            int rowsOpen = qp.getCapacityForTest() / colsOpen;

            System.out.println("[measurement] Quick Add tile rows — keyboard CLOSED: " + rowsClosed
                    + " rows (" + colsClosed + " cols); keyboard OPEN: " + rowsOpen
                    + " rows (" + colsOpen + " cols); keyboard height=" + qp.keyboardHeightForTest() + "px");

            // recomputeCapacity rebuilt the tile grid; lay out once more so the shrunk grid paints
            // its tiles above the keyboard rather than leaving the area blank.
            layoutAll(content);
            BufferedImage img = new BufferedImage(1512, 982, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try {
                content.printAll(g);
            } finally {
                g.dispose();
            }
            ImageIO.write(img, "PNG", target);
            System.out.println("wrote " + target.getName());
        } finally {
            view.dispose();
        }
    }

    /** Recursively lays out a container top-to-bottom so nested cards report their real bounds. */
    private static void layoutAll(java.awt.Container c) {
        c.doLayout();
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof java.awt.Container ct) layoutAll(ct);
        }
    }

    /** Renders the full window with {@code count} basket lines and writes it to {@code target}. */
    static void snapshot(int count, File target) throws Exception {
        CustomerView view = new CustomerView("Rocket POS — snapshot", quickAddItems(), noop());
        view.setSize(new Dimension(1512, 982));
        view.setVisible(true);
        view.validate();
        if (count > 0) {
            BigDecimal subtotal = new BigDecimal(count).setScale(2);
            view.updateBasket(basket(count), subtotal, BigDecimal.ZERO,
                    subtotal.multiply(new BigDecimal("0.07")).setScale(2, java.math.RoundingMode.HALF_UP),
                    subtotal);
        }
        try {
            Thread.sleep(300); // let one paint pass + the density animation settle
            view.validate();
            BufferedImage img = new BufferedImage(view.getWidth(), view.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try {
                view.printAll(g);
            } finally {
                g.dispose();
            }
            ImageIO.write(img, "PNG", target);
            System.out.println("wrote " + target.getName());
        } finally {
            view.dispose();
        }
    }

    private static List<Item> quickAddItems() {
        List<Item> items = new ArrayList<>();
        String[] names = {"Coca-Cola Can", "Diet Coke 20oz", "Pepsi 20oz", "Sprite 20oz",
                "Polar Pop Medium", "Pepperoni Pizza Slice", "Glazed Donut", "Lay's Regular",
                "Ruffles Cheddar 2.5oz", "Banana", "Milk 1L", "Smart Water 20oz",
                "Reese's PB Cup King", "M&M Peanut 1.74oz", "Red Bull 12oz", "Monster 16oz",
                "Gatorade Blue 28oz", "Snickers Bar", "Doritos Nacho", "Water 500ml"};
        for (int i = 0; i < names.length; i++) {
            items.add(new Item(String.format("%012d", i + 1), names[i],
                    new BigDecimal(String.valueOf(1 + (i % 5) + (i % 3) * 0.25)).setScale(2, java.math.RoundingMode.HALF_UP)));
        }
        return items;
    }

    private static List<LineItem> basket(int count) {
        List<LineItem> out = new ArrayList<>(count);
        String[] names = {"Coca-Cola Can", "Marlboro Gold Pack", "Banana", "Glazed Donut With Hole",
                "Smart Water 20oz Bottle", "Reese's Peanut Butter Cup King Size"};
        for (int i = 0; i < count; i++) {
            Item item = new Item("UPC-" + i, names[i % names.length] + " " + i,
                    new BigDecimal(String.valueOf(1 + (i % 7) * 0.5)).setScale(2, java.math.RoundingMode.HALF_UP));
            out.add(new LineItem(item, i % 4 == 0 ? 2 : 1));
        }
        return out;
    }

    private static IPosEventDispatcher noop() {
        return e -> { };
    }
}
