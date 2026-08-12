package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;

import javax.imageio.ImageIO;
import javax.swing.ButtonModel;
import javax.swing.JComponent;
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

        // Summary tape: zero-discount and non-zero-discount side by side. Verifies right-edge
        // alignment holds when digit counts change and the Total row dominates.
        File summaryFile = new File(out, "summary-tape.png");
        ImageIO.write(composeSummaryTape(), "PNG", summaryFile);
        System.out.println("wrote " + summaryFile.getName());

        // Cash-mode-choice dialog: Next Dollar enabled (fractional total) vs disabled
        // (whole-dollar total), side by side. Shows the two terminal tiles, their tender figures,
        // the hairline, and the full-width Other Amount button.
        File choiceFile = new File(out, "cash-mode-choice.png");
        ImageIO.write(composeCashModeChoice(), "PNG", choiceFile);
        System.out.println("wrote " + choiceFile.getName());

        // Cash dialog with its always-on numeric keypad, and the change-quantity dialog with its
        // spinner + keypad. Rendered from the shipping dialogs so the on-screen input components
        // appear exactly as the cashier sees them. Each prints its packed height so a reviewer can
        // confirm the dialog still fits inside the 982px terminal.
        File cashKeypad = new File(out, "cash-dialog-keypad.png");
        ImageIO.write(composeCashDialogWithKeypad(), "PNG", cashKeypad);
        System.out.println("wrote " + cashKeypad.getName());

        File qtyKeypad = new File(out, "quantity-dialog-keypad.png");
        ImageIO.write(composeQuantityDialogWithKeypad(), "PNG", qtyKeypad);
        System.out.println("wrote " + qtyKeypad.getName());

        System.exit(0);
    }

    /** The cash dialog primed on a $7.30 basket, keypad in place. Prints its packed height. */
    private static BufferedImage composeCashDialogWithKeypad() {
        PayWithCashView dialog = new PayWithCashView(null, noop());
        dialog.setModal(false);
        try {
            dialog.openFor(new BigDecimal("7.30"), PayWithCashView.Mode.EXACT);
            System.out.println("[measurement] PayWithCashView packed height with keypad = "
                    + dialog.getHeight() + "px");
            dialog.setVisible(false);   // paint offscreen from a forced layout, not the live window
            return composeDialogContent(dialog, "CASH PAYMENT — KEYPAD");
        } finally {
            dialog.dispose();
        }
    }

    /** The change-quantity dialog with spinner + keypad. Prints its packed height. */
    private static BufferedImage composeQuantityDialogWithKeypad() {
        ChangeQuantityView dialog = new ChangeQuantityView(null, noop(), 999);
        dialog.setModal(false);
        try {
            dialog.openFor(new LineItem(new Item("UPC-W", "Widget 12 oz", new BigDecimal("1.99")), 2));
            System.out.println("[measurement] ChangeQuantityView packed height with keypad = "
                    + dialog.getHeight() + "px");
            dialog.setVisible(false);   // paint offscreen from a forced layout, not the live window
            return composeDialogContent(dialog, "CHANGE QUANTITY — SPINNER + KEYPAD");
        } finally {
            dialog.dispose();
        }
    }

    /** Paints a single dialog's content pane onto a PAPER tile with a caption underneath. */
    private static BufferedImage composeDialogContent(javax.swing.JDialog dialog, String label) {
        JComponent pane = (JComponent) dialog.getContentPane();
        int dialogW = pane.getWidth();
        int dialogH = pane.getHeight();
        int labelH = 26;
        int pad = 24;
        int totalW = dialogW + pad * 2;
        int totalH = dialogH + labelH + pad * 2;

        BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(PosTheme.PAPER);
            g.fillRect(0, 0, totalW, totalH);
            paintContentPane(g, pane, pad, pad, dialogW, dialogH);
            g.setFont(PosTheme.base(Font.BOLD, PosTheme.EYEBROW));
            g.setColor(PosTheme.MUTED);
            g.drawString(label, pad + (dialogW - g.getFontMetrics().stringWidth(label)) / 2,
                    pad + dialogH + labelH - 8);
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * Renders the actual {@link CashModeChoiceView} content pane twice: once on a fractional
     * total ($17.70 → Next Dollar live at $18.00) and once on a whole-dollar total ($18.00 →
     * Next Dollar disabled because it would duplicate Exact Amount). Uses the shipping dialog
     * body so tile figures, the hairline, and the Other Amount button are the real thing.
     */
    private static BufferedImage composeCashModeChoice() {
        CashModeChoiceView enabled = new CashModeChoiceView(null, noop());
        CashModeChoiceView disabled = new CashModeChoiceView(null, noop());
        try {
            enabled.applyAmounts(new BigDecimal("17.70"), new BigDecimal("18.00"));
            disabled.applyAmounts(new BigDecimal("18.00"), new BigDecimal("18.00"));
            enabled.pack();
            disabled.pack();

            JComponent c1 = (JComponent) enabled.getContentPane();
            JComponent c2 = (JComponent) disabled.getContentPane();
            int dialogW = Math.max(c1.getWidth(), c2.getWidth());
            int dialogH = Math.max(c1.getHeight(), c2.getHeight());

            int gap = 40;
            int labelH = 26;
            int pad = 24;
            int totalW = dialogW * 2 + gap + pad * 2;
            int totalH = dialogH + labelH + pad * 2;

            BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(PosTheme.PAPER);
                g.fillRect(0, 0, totalW, totalH);
                g.setFont(PosTheme.base(Font.BOLD, PosTheme.EYEBROW));
                g.setColor(PosTheme.MUTED);

                paintContentPane(g, c1, pad, pad, dialogW, dialogH);
                String lbl1 = "NEXT DOLLAR ENABLED ($17.70)";
                g.drawString(lbl1, pad + (dialogW - g.getFontMetrics().stringWidth(lbl1)) / 2,
                        pad + dialogH + labelH - 8);

                int x2 = pad + dialogW + gap;
                paintContentPane(g, c2, x2, pad, dialogW, dialogH);
                String lbl2 = "NEXT DOLLAR DISABLED ($18.00)";
                g.drawString(lbl2, x2 + (dialogW - g.getFontMetrics().stringWidth(lbl2)) / 2,
                        pad + dialogH + labelH - 8);
            } finally {
                g.dispose();
            }
            return img;
        } finally {
            enabled.dispose();
            disabled.dispose();
        }
    }

    private static void paintContentPane(Graphics2D dst, JComponent pane, int x, int y,
                                         int w, int h) {
        pane.setSize(w, h);
        layoutAll(pane);
        Graphics2D sub = (Graphics2D) dst.create(x, y, w, h);
        try {
            pane.paint(sub);
        } finally {
            sub.dispose();
        }
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

    /**
     * Renders the summary tape twice — first with a zero discount, then with a non-zero one —
     * side by side, so the anti-layout-shift guarantee (identical heights) is visible on the
     * composite and right-edge alignment can be eyeballed across differing digit counts.
     */
    private static BufferedImage composeSummaryTape() {
        int tapeW = 380;
        int tapeH = 160;
        int gap = 40;
        int labelH = 26;
        int pad = 24;
        int totalW = tapeW * 2 + gap + pad * 2;
        int totalH = tapeH + labelH + pad * 2;

        BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(PosTheme.PAPER);
            g.fillRect(0, 0, totalW, totalH);
            g.setFont(PosTheme.base(Font.BOLD, PosTheme.EYEBROW));
            g.setColor(PosTheme.MUTED);

            paintSummaryTape(g, pad, pad, tapeW, tapeH,
                    new BigDecimal("17.70"), BigDecimal.ZERO,
                    new BigDecimal("1.24"), new BigDecimal("18.94"));
            String lbl1 = "ZERO DISCOUNT";
            g.drawString(lbl1, pad + (tapeW - g.getFontMetrics().stringWidth(lbl1)) / 2,
                    pad + tapeH + labelH - 8);

            int x2 = pad + tapeW + gap;
            paintSummaryTape(g, x2, pad, tapeW, tapeH,
                    new BigDecimal("109.99"), new BigDecimal("12.00"),
                    new BigDecimal("6.86"), new BigDecimal("104.85"));
            String lbl2 = "NON-ZERO DISCOUNT";
            g.drawString(lbl2, x2 + (tapeW - g.getFontMetrics().stringWidth(lbl2)) / 2,
                    pad + tapeH + labelH - 8);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void paintSummaryTape(Graphics2D dst, int x, int y, int w, int h,
                                         BigDecimal subtotal, BigDecimal discount,
                                         BigDecimal tax, BigDecimal total) {
        CustomerView view = new CustomerView("snapshot", List.of(), noop());
        try {
            view.setSize(new Dimension(1412, 882));
            view.doLayout();
            view.updateBasket(List.of(), subtotal, discount, tax, total);

            JPanel tape = view.getSummaryTapeForTest();
            tape.setSize(w, h);
            tape.doLayout();
            layoutAll(tape);

            // Match the surrounding column background so the tape doesn't render on the raw
            // PAPER — SURFACE is what appears in the shipping basket column.
            Graphics2D sub = (Graphics2D) dst.create(x, y, w, h);
            try {
                sub.setColor(PosTheme.SURFACE);
                sub.fillRect(0, 0, w, h);
                tape.paint(sub);
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
                    "com.rocketpartners.onboarding.possystem.display.QuickAddPanel$QuickAddTile");
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
