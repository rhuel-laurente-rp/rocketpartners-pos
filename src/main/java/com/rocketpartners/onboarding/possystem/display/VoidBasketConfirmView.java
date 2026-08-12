package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Modal void-basket confirmation dialog: {@link PosDialog}-shelled, matches
 * {@link ChangeQuantityView} conventions in structure and footer.
 *
 * <p><strong>Vocabulary discipline.</strong> "Void Basket" ends the transaction; a dialog's
 * "Cancel" only dismisses. The two words mean different things and are kept apart on purpose,
 * so this dialog names its secondary button {@code Keep Basket} rather than {@code Cancel} —
 * a cashier who mis-taps and reaches for a familiar Cancel button must never lose a basket.</p>
 *
 * <p><strong>Footer.</strong> Positional order matches {@link ChangeQuantityView} — the danger
 * button on the right, the safe button on the left — so a cashier's muscle memory carries
 * across dialogs. Both buttons are sized identically.</p>
 *
 * <p><strong>Keyboard defaults are deliberately inverted.</strong> Every other dialog in the
 * POS makes the primary (affirmative) action the keyboard default. This dialog is the single
 * exception: Void Basket keeps the danger-styled primary slot on the right so the layout is
 * predictable, but initial focus, the root pane's default button, and ESC all point at
 * {@code Keep Basket}. A destructive action must not fire from a stray Enter or a barcode
 * scanner's terminator keystroke — the only path to a void is a deliberate tap on the danger
 * button. Without this inversion the dialog would look identical to every other one and read
 * like an oversight in review.</p>
 *
 * <p><strong>Approval seam.</strong> Real registers often require manager approval to void a
 * transaction, especially post-Total. That step is out of scope for this build, but the flow is
 * structured so that step can be inserted between the cashier's confirm press and the
 * dispatched {@link PosEventType#VOID_BASKET_CONFIRM_PRESSED} event — the {@link #onConfirm()}
 * handler is a single call site.</p>
 */
public class VoidBasketConfirmView extends PosDialog {

    /** Body width floor so the description and summary lay out on one line each. */
    private static final int BODY_MIN_WIDTH = 380;

    private final IPosEventDispatcher dispatcher;

    private final JLabel descriptionLabel = new JLabel();
    private final JLabel summaryLabel = new JLabel();
    private final PosButton voidButton;
    private final PosButton keepButton;

    /** Cached at open time so the decline event carries the same figures the cashier saw. */
    private int itemCount;
    private BigDecimal grandTotal = BigDecimal.ZERO;

    /**
     * @param owner      the parent frame; may be {@code null}
     * @param dispatcher target for view-input events; must not be {@code null}
     */
    public VoidBasketConfirmView(JFrame owner, IPosEventDispatcher dispatcher) {
        super(owner, "Void Basket?");
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        this.dispatcher = dispatcher;

        this.voidButton = PosButtons.danger("Void Basket");
        this.keepButton = PosButtons.secondary("Keep Basket");

        setBody(buildBody());

        voidButton.addActionListener(e -> onConfirm());
        setPrimary(voidButton);

        keepButton.addActionListener(e -> onDecline());
        addSecondary(keepButton);

        // Keep basket is the keyboard default — a deliberate departure from every other dialog,
        // where the primary is also the default. The destructive action must never fire from a
        // stray Enter or scanner terminator. ESC is wired to the same path.
        setCancelAction(this::onDecline);
        setInitialFocus(keepButton);
        getRootPane().setDefaultButton(keepButton);

        matchFooterButtonSizes();
    }

    // ---- Public API called by CustomerViewController ----------------------

    /**
     * Populates and opens the dialog for a fresh confirmation.
     *
     * @param itemCount  sum of quantities across non-voided line items; must be non-negative
     * @param grandTotal grand total at the moment of the prompt; must not be {@code null}
     */
    public void openFor(int itemCount, BigDecimal grandTotal) {
        if (itemCount < 0) {
            throw new IllegalArgumentException("itemCount must be non-negative, got " + itemCount);
        }
        if (grandTotal == null) throw new IllegalArgumentException("grandTotal must not be null");
        this.itemCount = itemCount;
        this.grandTotal = grandTotal.setScale(2, RoundingMode.HALF_UP);
        descriptionLabel.setText("This will discard the whole sale.");
        summaryLabel.setText(itemCount + (itemCount == 1 ? " item" : " items")
                + " · " + PosTheme.money(this.grandTotal));
        openDialog();
    }

    // ---- Handlers ---------------------------------------------------------

    private void onConfirm() {
        // Single call site — a future manager-approval step slots in here without changing
        // any of the routing above or below.
        closeDialog();
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_CONFIRM_PRESSED));
    }

    private void onDecline() {
        closeDialog();
        Map<String, Object> props = new HashMap<>();
        props.put("itemCount", itemCount);
        props.put("grandTotal", grandTotal);
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_DECLINED, props));
    }

    // ---- Test hooks -------------------------------------------------------

    PosButton getVoidButtonForTest() {
        return voidButton;
    }

    PosButton getKeepButtonForTest() {
        return keepButton;
    }

    JLabel getSummaryLabelForTest() {
        return summaryLabel;
    }

    JLabel getDescriptionLabelForTest() {
        return descriptionLabel;
    }

    int getItemCountForTest() {
        return itemCount;
    }

    BigDecimal getGrandTotalForTest() {
        return grandTotal;
    }

    // ---- Internals --------------------------------------------------------

    private JPanel buildBody() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        descriptionLabel.setFont(PosTheme.base(Font.PLAIN, PosTheme.ROW));
        descriptionLabel.setForeground(PosTheme.INK);
        descriptionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(descriptionLabel);

        body.add(Box.createVerticalStrut(10));

        summaryLabel.setFont(PosTheme.base(Font.BOLD, PosTheme.HEADLINE));
        summaryLabel.setForeground(PosTheme.INK);
        summaryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(summaryLabel);

        // Width floor. Dialogs pack to their preferred size, so the summary and description
        // labels alone don't establish a stable width — one long amount would let the dialog
        // grow to fit and vice versa. Pinning the body width here keeps the two buttons at a
        // consistent visual weight across opens.
        JComponent strut = (JComponent) Box.createRigidArea(new Dimension(BODY_MIN_WIDTH, 0));
        strut.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(strut);

        return body;
    }

    private void matchFooterButtonSizes() {
        // Void and Keep report identical widths and heights. PosDialog#setPrimary sizes the
        // primary to BUTTON_HEIGHT_PRIMARY + SHADOW_INSET; mirror the same on Keep basket and
        // take the wider of the two natural widths so neither is cut off.
        Dimension voidPref = voidButton.getPreferredSize();
        Dimension keepPref = keepButton.getPreferredSize();
        Dimension shared = new Dimension(
                Math.max(voidPref.width, keepPref.width),
                Math.max(voidPref.height, keepPref.height));
        voidButton.setPreferredSize(shared);
        keepButton.setPreferredSize(shared);
    }
}
