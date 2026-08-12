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
        snapshot(3, new File(out, "customerview-3.png"));
        snapshot(40, new File(out, "customerview-40.png"));
        snapshotWithKeyboard(new File(out, "quickadd-qwerty-open.png"));
        System.out.println("Wrote full-window snapshots to " + out.getAbsolutePath());
        System.exit(0);
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
