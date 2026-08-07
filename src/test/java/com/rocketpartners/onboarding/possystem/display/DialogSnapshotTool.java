package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;

import javax.imageio.ImageIO;
import javax.swing.ButtonModel;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Manual snapshot harness that renders each {@link PosButton} variant in each of its three
 * critical states — resting, pressed, disabled — into a single composite PNG per variant, so a
 * reviewer can compare "elevated / sunk / flat" side by side without walking through the app.
 *
 * <p>Not a JUnit test. Invoked by hand from the IDE or via
 * {@code ./gradlew runDialogSnapshots} (registered in {@code build.gradle} when a run task
 * exists). Skips silently when the JVM is headless so CI never bombs.</p>
 *
 * <p>Each composite is laid out horizontally at 320×80 per cell and labelled underneath, so a
 * PR can attach the four PNGs and get a "you see the whole button system at a glance" review.
 * The output tile bakes {@link PosTheme#PAPER} into the background rather than transparency —
 * the resting-state shadow is drawn in translucent black and would be invisible on a
 * checkerboard.</p>
 */
public final class DialogSnapshotTool {

    private static final int CELL_W = 320;
    /**
     * Cell height sized to the quick-add tile — the tallest variant we render. Sizing every
     * cell the same lets the composite line up cleanly across variants.
     */
    private static final int CELL_H = 120;
    /** Width the button occupies inside a cell — leaves a margin so the shadow isn't clipped. */
    private static final int BUTTON_W = 200;
    private static final int LABEL_H = 22;
    private static final int PAD = 20;

    private DialogSnapshotTool() {}

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("headless environment — skipping snapshot render");
            return;
        }
        File out = new File("build/snapshots");
        if (!out.exists() && !out.mkdirs()) {
            throw new IllegalStateException("could not create " + out.getAbsolutePath());
        }
        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant("primary", () -> PosButtons.primary("Total")));
        variants.add(new Variant("secondary", () -> PosButtons.secondary("Cancel")));
        variants.add(new Variant("danger", () -> PosButtons.danger("Void Basket")));
        variants.add(new Variant("tender-cash", () -> PosButtons.tender("Pay Cash", PosTheme.GO)));
        variants.add(new Variant("tender-debit",
                () -> PosButtons.tender("Pay Debit", PosTheme.CARD_DEBIT)));
        variants.add(new Variant("tender-credit",
                () -> PosButtons.tender("Pay Credit", PosTheme.CARD_CREDIT)));
        variants.add(new Variant("quickadd", DialogSnapshotTool::buildQuickAddTile));
        for (Variant v : variants) {
            File file = new File(out, "button-" + v.name + ".png");
            ImageIO.write(compose(v), "PNG", file);
            System.out.println("wrote " + file.getName());
        }

        // Full tender column: enabled left, disabled right. Renders the actual CustomerView so
        // what you see is the shipping layout — column proportions, amount-due readout, and
        // three fills side by side.
        File columnFile = new File(out, "tender-column.png");
        ImageIO.write(composeTenderColumn(), "PNG", columnFile);
        System.out.println("wrote " + columnFile.getName());

        System.exit(0);
    }

    private static BufferedImage compose(Variant v) {
        int w = CELL_W * 3 + PAD * 4;
        int h = CELL_H + LABEL_H + PAD * 2;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(PosTheme.PAPER);
            g.fillRect(0, 0, w, h);
            g.setFont(PosTheme.base(Font.BOLD, PosTheme.EYEBROW));
            g.setColor(PosTheme.MUTED);
            String[] states = {"RESTING", "PRESSED", "DISABLED"};
            for (int i = 0; i < 3; i++) {
                int x = PAD + i * (CELL_W + PAD);
                int y = PAD;
                paintButton(g, v, states[i], x, y);
                int labelX = x + (CELL_W - g.getFontMetrics().stringWidth(states[i])) / 2;
                g.drawString(states[i], labelX, y + CELL_H + LABEL_H - 6);
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void paintButton(Graphics2D g, Variant v, String state, int x, int y) {
        PosButton button = v.factory.get();
        int naturalHeight = button.getPreferredSize().height;
        int height = Math.max(naturalHeight, PosTheme.BUTTON_HEIGHT_PRIMARY + PosButton.SHADOW_INSET);
        // The quick-add tile factory hands back a 10 px wide template because in the app the
        // grid stretches it. For the snapshot, size the button directly and hand it a
        // {@link javax.swing.CellRendererPane}-style throwaway parent so its EDT expectations
        // are satisfied without a real container layout pass.
        button.setSize(BUTTON_W, height);

        JPanel host = new JPanel(null);
        host.setBackground(PosTheme.PAPER);
        host.setSize(CELL_W, CELL_H);
        int bx = (CELL_W - BUTTON_W) / 2;
        int by = (CELL_H - height) / 2;
        button.setLocation(bx, by);
        host.add(button);

        switch (state) {
            case "RESTING":
                // default; no-op.
                break;
            case "PRESSED":
                pressAndArm(button);
                break;
            case "DISABLED":
                button.setEnabled(false);
                break;
            default:
                throw new IllegalArgumentException(state);
        }

        Graphics2D sub = (Graphics2D) g.create(x, y, CELL_W, CELL_H);
        try {
            host.paint(sub);
        } finally {
            sub.dispose();
        }
    }

    /**
     * Renders the actual tender column from a fresh {@link CustomerView}, once with the trio
     * disabled (the state before Total is pressed) and once enabled (post-Total), side by side.
     * Uses the shipping layout — column proportions, DISPLAY amount-due readout, full-height
     * button stack — so the snapshot mirrors what a cashier sees.
     */
    private static BufferedImage composeTenderColumn() {
        int columnW = 380;
        int columnH = 640;
        int gap = 40;
        int labelH = 26;
        int pad = 24;
        int totalW = columnW * 2 + gap + pad * 2;
        int totalH = columnH + labelH + pad * 2;

        BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(PosTheme.PAPER);
            g.fillRect(0, 0, totalW, totalH);
            g.setFont(PosTheme.base(Font.BOLD, PosTheme.EYEBROW));
            g.setColor(PosTheme.MUTED);

            paintTenderColumn(g, pad, pad, columnW, columnH, false, new BigDecimal("42.17"));
            String lbl1 = "DISABLED (before Total)";
            g.drawString(lbl1, pad + (columnW - g.getFontMetrics().stringWidth(lbl1)) / 2,
                    pad + columnH + labelH - 8);

            int x2 = pad + columnW + gap;
            paintTenderColumn(g, x2, pad, columnW, columnH, true, new BigDecimal("42.17"));
            String lbl2 = "ENABLED (after Total)";
            g.drawString(lbl2, x2 + (columnW - g.getFontMetrics().stringWidth(lbl2)) / 2,
                    pad + columnH + labelH - 8);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void paintTenderColumn(Graphics2D dst, int x, int y, int w, int h,
                                          boolean enabled, BigDecimal amountDue) {
        CustomerView view = new CustomerView("snapshot", List.of(), noop());
        try {
            view.setSize(new Dimension(1412, 882));
            view.doLayout();
            view.updateBasket(List.of(), amountDue);
            view.setTenderInputEnabled(enabled);

            JPanel column = view.getTenderColumnForTest();
            column.setSize(w, h);
            column.doLayout();
            // Force a bottom-up layout so nested cards, grids, and card containers all report
            // their new bounds before we paint. Without this the buttons render at their
            // pre-resize sizes and the composite gets a squashed column.
            layoutAll(column);

            Graphics2D sub = (Graphics2D) dst.create(x, y, w, h);
            try {
                column.paint(sub);
            } finally {
                sub.dispose();
            }
        } finally {
            view.dispose();
        }
    }

    private static void layoutAll(java.awt.Container c) {
        c.doLayout();
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof java.awt.Container ct) layoutAll(ct);
        }
    }

    private static IPosEventDispatcher noop() {
        return event -> {};
    }

    private static PosButton buildQuickAddTile() {
        // Route through CustomerView's factory so what we render is the actual tile shipped in
        // the app, not a lookalike. If the class ever gains a package-private hook we can call
        // it directly; today it lives inside CustomerView so we construct one via reflection.
        try {
            Class<?> tileClass = Class.forName(
                    "com.rocketpartners.onboarding.possystem.display.CustomerView$QuickAddTile");
            java.lang.reflect.Constructor<?> ctor =
                    tileClass.getDeclaredConstructor(String.class, String.class);
            ctor.setAccessible(true);
            return (PosButton) ctor.newInstance("Cola 12 oz", "$1.99");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not build QuickAddTile snapshot", e);
        }
    }

    private static void pressAndArm(PosButton button) {
        ButtonModel model = button.getModel();
        model.setArmed(true);
        model.setPressed(true);
    }

    private record Variant(String name, Supplier<PosButton> factory) {}
}
