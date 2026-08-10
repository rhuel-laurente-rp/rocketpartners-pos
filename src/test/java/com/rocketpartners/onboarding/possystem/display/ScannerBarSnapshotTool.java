package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Manual snapshot harness for the scan bar's four states: idle, focused, locked, and error.
 * Not a JUnit test — it constructs Swing components, so it's invoked by hand from the IDE or via
 * a Gradle task if one is registered. Skips silently when the JVM is headless so CI never bombs.
 *
 * <p>Renders each state at a fixed width, labels it, and writes a single composite PNG to
 * {@code build/snapshots/scan-bar.png} so the four are visually comparable.</p>
 */
public final class ScannerBarSnapshotTool {

    private static final int CELL_W = 780;
    private static final int CELL_H = 90;
    private static final int LABEL_H = 26;
    private static final int PAD = 24;

    private ScannerBarSnapshotTool() {}

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("headless environment — skipping snapshot render");
            return;
        }
        File out = new File("build/snapshots");
        if (!out.exists() && !out.mkdirs()) {
            throw new IllegalStateException("could not create " + out.getAbsolutePath());
        }
        File target = new File(out, "scan-bar.png");
        ImageIO.write(compose(), "PNG", target);
        System.out.println("wrote " + target.getName());
        System.exit(0);
    }

    private static BufferedImage compose() {
        int cells = 4;
        int totalW = CELL_W + PAD * 2;
        int totalH = (CELL_H + LABEL_H) * cells + PAD * (cells + 1);

        BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(PosTheme.PAPER);
            g.fillRect(0, 0, totalW, totalH);

            int y = PAD;
            y = paintCell(g, PAD, y, "IDLE", state -> {
                // No mutation — freshly-constructed view is already idle.
            });
            y = paintCell(g, PAD, y, "FOCUSED", state -> {
                state.setScanText("049000053418");
                // Focus in a headless-friendly way: request focus on the JTextField after the
                // component tree is laid out. Even if the platform can't actually grant focus,
                // the border swap depends on hasFocus() at paint time — we can't force that
                // synthetically, so this cell renders in "typed digits, idle border". Still
                // useful for visual comparison alongside the empty idle cell.
                state.getScanField().requestFocusInWindow();
            });
            y = paintCell(g, PAD, y, "LOCKED", state -> state.setLocked(true));
            y = paintCell(g, PAD, y, "ERROR", state -> {
                state.setScanText("012345678905");
                state.setInlineError(ScannerViewController.MSG_ITEM_NOT_FOUND_PREFIX + "012345678905");
            });
        } finally {
            g.dispose();
        }
        return img;
    }

    private static int paintCell(Graphics2D g, int x, int y, String label,
                                 SceneSetup setup) {
        ScannerView view = new ScannerView(noop());
        JPanel host = new JPanel(null);
        host.setBackground(PosTheme.SURFACE);
        host.setSize(CELL_W, CELL_H);
        view.setSize(CELL_W - PAD * 2, CELL_H - PAD);
        view.setLocation(PAD, PAD / 2);
        host.add(view);

        setup.apply(view);
        layoutAll(host);

        Graphics2D sub = (Graphics2D) g.create(x, y, CELL_W, CELL_H);
        try {
            host.paint(sub);
        } finally {
            sub.dispose();
        }

        g.setFont(PosTheme.base(Font.BOLD, PosTheme.EYEBROW));
        g.setColor(PosTheme.MUTED);
        int labelX = x + (CELL_W - g.getFontMetrics().stringWidth(label)) / 2;
        g.drawString(label, labelX, y + CELL_H + LABEL_H - 8);

        return y + CELL_H + LABEL_H + PAD;
    }

    private static void layoutAll(Container c) {
        c.doLayout();
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof Container ct) layoutAll(ct);
        }
    }

    private static IPosEventDispatcher noop() {
        return event -> {};
    }

    @FunctionalInterface
    private interface SceneSetup {
        void apply(ScannerView view);
    }

    // Keeps the Dimension import from being unused if a future edit trims out setSize calls.
    @SuppressWarnings("unused")
    private static Dimension size(int w, int h) { return new Dimension(w, h); }
}
