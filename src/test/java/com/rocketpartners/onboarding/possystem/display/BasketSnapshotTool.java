package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual snapshot harness for eyeballing the basket at the density transition. Not a JUnit
 * test — it opens a real display, so it's invoked by hand:
 * {@code ./gradlew runBasketSnapshots} (or run this class directly from your IDE).
 *
 * <p>Renders the basket at 3, 10, 11, and 40 items — the density-transition boundaries — and
 * writes PNGs to {@code build/snapshots}. Useful when tweaking pixel budgets or comparing
 * before/after design iterations. Skips silently in headless environments so it does not
 * bomb CI.</p>
 */
public final class BasketSnapshotTool {

    private BasketSnapshotTool() {}

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("headless environment — skipping snapshot render");
            return;
        }
        int[] counts = {3, 10, 11, 40};
        File out = new File("build/snapshots");
        if (!out.exists() && !out.mkdirs()) {
            throw new IllegalStateException("could not create " + out.getAbsolutePath());
        }
        for (int count : counts) {
            snapshot(count, new File(out, "basket-" + count + ".png"));
        }
        System.out.println("Wrote snapshots to " + out.getAbsolutePath());
        System.exit(0);
    }

    private static void snapshot(int count, File target) throws Exception {
        CustomerView view = new CustomerView("Rocket POS — snapshot", List.of(), noop());
        view.setSize(new Dimension(1412, 882));
        view.updateBasket(build(count), new BigDecimal(count).setScale(2));
        view.setVisible(true);
        try {
            // Give Swing one paint pass to settle and let the density animation finish.
            Thread.sleep(250);
            BufferedImage img = new BufferedImage(view.getWidth(), view.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            try {
                view.paint(g);
            } finally {
                g.dispose();
            }
            ImageIO.write(img, "PNG", target);
            System.out.println("wrote " + target.getName());
        } finally {
            view.dispose();
        }
    }

    private static List<LineItem> build(int count) {
        List<LineItem> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Item item = new Item("UPC-" + i,
                    "Item " + i + " Description",
                    new BigDecimal("1.00"));
            out.add(new LineItem(item, i % 4 == 0 ? 2 : 1));
        }
        return out;
    }

    private static IPosEventDispatcher noop() {
        return e -> {};
    }
}
