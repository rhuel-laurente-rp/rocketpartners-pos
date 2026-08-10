package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;

/**
 * Modal receipt dialog.
 *
 * <p>Deliberately breaks the plain-surface style used by the other dialogs to render as
 * <em>receipt tape</em>: {@link PosTheme#PAPER} background, monospaced font, {@link
 * PosTheme#RULE} hairlines top and bottom of the tape block, and the tape inset from the
 * dialog edges so it reads as a strip of paper rather than a panel. The rest of the dialog —
 * header, footer, keyboard bindings — inherits the standard {@link PosDialog} chrome.</p>
 *
 * <p>The receipt text still comes verbatim from
 * {@code TransactionService.generateReceipt()} — the view formats nothing.</p>
 */
public class ReceiptView extends PosDialog {

    private static final int PREFERRED_WIDTH = 520;
    private static final int PREFERRED_HEIGHT = 560;
    /**
     * Cap the visible receipt tape at this height regardless of receipt length. Without a cap,
     * pack() would grow the dialog to the JTextArea's full preferred height and a long basket
     * would push the primary button off the bottom of the screen — the scroll pane exists but
     * only engages when the viewport can't hold the text. Chosen so a ~30-line receipt
     * (well past a typical convenience-store sale) still shows a slice big enough to read
     * without scrolling.
     */
    private static final int TAPE_MAX_HEIGHT = 460;

    private final JTextArea textArea = new JTextArea();

    /**
     * Wired by the caller to unlock the scan bar and put the caret back on the scan field on
     * dismiss. Defaults to a no-op so tests that don't need a live scan bar don't have to
     * inject one; {@link Application} wires it to {@link ScannerView#setLocked(boolean)} plus
     * {@link ScannerView#requestScanFieldFocus()}.
     */
    private Runnable onDismissed = () -> {};

    /**
     * @param owner      the parent frame; may be {@code null}
     * @param dispatcher target for view-input events; must not be {@code null}
     */
    public ReceiptView(JFrame owner, IPosEventDispatcher dispatcher) {
        super(owner, "Receipt");
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");

        setBody(buildBody());
        setMinimumSize(new Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT));

        PosButton startNext = PosButtons.primary("Start Next Sale");
        startNext.addActionListener(e -> onStartNextSale(dispatcher));
        setPrimary(startNext);
        setCancelAction(() -> onStartNextSale(dispatcher));
        setInitialFocus(startNext);
    }

    /**
     * Installs a callback fired on Start Next Sale / ESC — used by {@link Application} to
     * unlock the scan bar and refocus its scan field immediately, without waiting for the
     * {@link PosEventType#RECEIPT_DISMISSED} chain to arrive. The event-driven reset still
     * runs, but a scan-bar left in the locked mode across dismissal is exactly the class of
     * race that dropped through the cracks the last time this file was worked on, and one
     * direct call closes it.
     *
     * @param callback runnable invoked on dismiss; may be {@code null} for no-op
     */
    public void setOnDismissed(Runnable callback) {
        this.onDismissed = callback == null ? () -> {} : callback;
    }

    private void onStartNextSale(IPosEventDispatcher dispatcher) {
        // Direct call first so the scan bar re-renders before any modal-close painting can pin
        // the locked mode in the cashier's field of view; the event chain below still runs and
        // is what other subscribers (CustomerViewController, JournalListener) act on.
        onDismissed.run();
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.RECEIPT_DISMISS_PRESSED));
    }

    // ---- Public API called by ReceiptViewController ------------------------

    public void setReceiptText(String text) {
        textArea.setText(text == null ? "" : text);
        textArea.setCaretPosition(0);
    }

    // ---- Layout -----------------------------------------------------------

    private JPanel buildBody() {
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);

        textArea.setEditable(false);
        textArea.setFocusable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setBackground(PosTheme.PAPER);
        textArea.setForeground(PosTheme.INK);
        textArea.setLineWrap(false);
        textArea.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        JScrollPane scroll = new JScrollPane(textArea);
        scroll.getViewport().setBackground(PosTheme.PAPER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        // Cap and clamp the tape height. pack() would otherwise honour the text area's full
        // preferred height for a long receipt; the maximum size stops the layout from growing
        // past the cap, and the preferred size gives pack() something sane to aim for when the
        // receipt is short.
        scroll.setPreferredSize(new Dimension(PREFERRED_WIDTH - 40, TAPE_MAX_HEIGHT));
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, TAPE_MAX_HEIGHT));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        // Tape block: PAPER surface with RULE hairlines top and bottom; inset from the
        // dialog edges by leaving a stripe of SURFACE around it.
        JPanel tape = new JPanel(new BorderLayout());
        tape.setBackground(PosTheme.PAPER);
        tape.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, PosTheme.RULE));
        tape.add(scroll, BorderLayout.CENTER);

        body.setBorder(BorderFactory.createEmptyBorder(4, 16, 4, 16));
        body.add(tape, BorderLayout.CENTER);
        return body;
    }
}
