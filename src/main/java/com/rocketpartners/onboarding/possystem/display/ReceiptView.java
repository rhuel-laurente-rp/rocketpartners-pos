package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;

/**
 * Modal receipt dialog: a dumb Swing renderer that shows a preformatted receipt string in a
 * scrollable monospaced text area, with a single {@code Dismiss} button.
 *
 * <p>The view does not re-derive or re-format anything — it takes the string produced by
 * {@link com.rocketpartners.onboarding.possystem.service.TransactionService#generateReceipt}
 * verbatim and paints it. Money formatting, column alignment, voided-line filtering, and any
 * other layout decisions live in the service. If the receipt layout needs to change, it changes
 * there, and this view redraws whatever comes back.</p>
 *
 * <p>Outbound: on {@code Dismiss}, dispatches {@link PosEventType#RECEIPT_DISMISS_PRESSED}.</p>
 *
 * <p>Inbound: {@link #setReceiptText(String)}, {@link #openDialog()}, {@link #closeDialog()}
 * for the controller to drive the dialog lifecycle.</p>
 */
public class ReceiptView {

    private static final int PREFERRED_WIDTH = 480;
    private static final int PREFERRED_HEIGHT = 520;

    private final JDialog dialog;

    private final JTextArea textArea = new JTextArea();
    private final JButton dismissButton = new JButton("Dismiss");

    /**
     * @param owner      the parent frame; may be {@code null}
     * @param dispatcher target for view-input events; must not be {@code null}
     */
    public ReceiptView(JFrame owner, IPosEventDispatcher dispatcher) {
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        this.dialog = new JDialog(owner, "Receipt", true);
        this.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        this.dialog.setPreferredSize(new Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT));

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        textArea.setEditable(false);
        textArea.setFocusable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setLineWrap(false);

        JScrollPane scroll = new JScrollPane(textArea);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        content.add(scroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        south.add(dismissButton);
        content.add(south, BorderLayout.SOUTH);

        dismissButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.RECEIPT_DISMISS_PRESSED)));

        dialog.getContentPane().add(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
    }

    // ---- Public API called by ReceiptViewController ------------------------

    /**
     * Replaces the on-screen receipt text. The string is displayed verbatim in a monospaced
     * text area, so the caller (the service) controls column alignment.
     */
    public void setReceiptText(String text) {
        textArea.setText(text == null ? "" : text);
        textArea.setCaretPosition(0);
    }

    /** Opens the dialog. Blocks until it is closed (modal). */
    public void openDialog() {
        dialog.setVisible(true);
    }

    /** Hides the dialog. */
    public void closeDialog() {
        dialog.setVisible(false);
    }
}
