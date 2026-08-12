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

/**
 * Modal tender-confirmation dialog: {@link PosDialog}-shelled, modelled on
 * {@link VoidBasketConfirmView} in structure and footer. One class serves every tender that wants
 * a "are you sure?" step before it commits — the two one-tap cash modes (Exact Amount, Next
 * Dollar) and both card tenders (Debit, Credit) — the way {@link PayWithCardView} serves both card
 * types with one class.
 *
 * <p><strong>Context, not just a prompt.</strong> Every open shows the cashier what they are about
 * to commit: the amount in the dark header strip, a one-line description, and a bold summary line
 * naming the tender (e.g. {@code "Exact Amount · $7.30"} or {@code "Credit Card · $18.00"}). A
 * cashier confirms against a figure, never a bare yes/no.</p>
 *
 * <p><strong>Parameterised events.</strong> The confirm and cancel {@link PosEventType}s are passed
 * in at construction so a shared view never collides on the bus: the cash controller wires it to
 * {@link PosEventType#CASH_TENDER_CONFIRM_PRESSED} / {@link PosEventType#CASH_TENDER_BACK_PRESSED},
 * the card controller to {@link PosEventType#CARD_TENDER_CONFIRM_PRESSED} /
 * {@link PosEventType#CARD_TENDER_CANCELLED}. Each controller owns its own instance.</p>
 *
 * <p><strong>Footer.</strong> Positional order matches {@link VoidBasketConfirmView} — secondary
 * on the left, primary on the right — so muscle memory carries across dialogs. Unlike the
 * void-basket dialog, the keyboard default is <em>not</em> inverted: this is an ordinary commit
 * dialog, so Enter fires the primary (Confirm) and initial focus lands there, matching every
 * non-destructive dialog in the app.</p>
 */
public class TenderConfirmView extends PosDialog {

    /** Body width floor so the description and summary lay out on one line each. */
    private static final int BODY_MIN_WIDTH = 380;

    private final IPosEventDispatcher dispatcher;
    private final PosEventType confirmEventType;
    private final PosEventType cancelEventType;

    private final JLabel descriptionLabel = new JLabel();
    private final JLabel summaryLabel = new JLabel();
    private final PosButton confirmButton;
    private final PosButton cancelButton;

    /**
     * @param owner            the parent frame; may be {@code null}
     * @param dispatcher       target for view-input events; must not be {@code null}
     * @param confirmEventType dispatched when the cashier presses the primary (confirm) button;
     *                         must not be {@code null}
     * @param cancelEventType  dispatched when the cashier presses the secondary button or ESC;
     *                         must not be {@code null}
     * @param confirmLabel     Title-Case label for the primary button; must not be blank
     * @param cancelLabel      Title-Case label for the secondary button; must not be blank
     */
    public TenderConfirmView(JFrame owner, IPosEventDispatcher dispatcher,
                             PosEventType confirmEventType, PosEventType cancelEventType,
                             String confirmLabel, String cancelLabel) {
        super(owner, "Confirm Payment");
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        if (confirmEventType == null) throw new IllegalArgumentException("confirmEventType must not be null");
        if (cancelEventType == null) throw new IllegalArgumentException("cancelEventType must not be null");
        if (confirmLabel == null || confirmLabel.isBlank()) {
            throw new IllegalArgumentException("confirmLabel must not be blank");
        }
        if (cancelLabel == null || cancelLabel.isBlank()) {
            throw new IllegalArgumentException("cancelLabel must not be blank");
        }
        this.dispatcher = dispatcher;
        this.confirmEventType = confirmEventType;
        this.cancelEventType = cancelEventType;

        this.confirmButton = PosButtons.primary(confirmLabel);
        this.cancelButton = PosButtons.secondary(cancelLabel);

        setBody(buildBody());

        confirmButton.addActionListener(e -> onConfirm());
        setPrimary(confirmButton);

        cancelButton.addActionListener(e -> onCancel());
        addSecondary(cancelButton);

        // Ordinary commit dialog: primary is the keyboard default, ESC cancels. (The void-basket
        // dialog inverts this on purpose; a tender confirmation does not — it is the affirmative
        // next step of a flow the cashier has already committed to by choosing a tender.)
        setCancelAction(this::onCancel);
        setInitialFocus(confirmButton);

        matchFooterButtonSizes();
    }

    // ---- Public API called by the tender controllers ----------------------

    /**
     * Populates and opens the dialog for a fresh confirmation.
     *
     * @param title       header-strip title; must not be {@code null}
     * @param description one-line description under the header; must not be {@code null}
     * @param summary     bold summary line naming the tender and amount; must not be {@code null}
     * @param amount      amount rendered in the header strip; must not be {@code null}
     */
    public void openFor(String title, String description, String summary, BigDecimal amount) {
        if (title == null) throw new IllegalArgumentException("title must not be null");
        if (description == null) throw new IllegalArgumentException("description must not be null");
        if (summary == null) throw new IllegalArgumentException("summary must not be null");
        if (amount == null) throw new IllegalArgumentException("amount must not be null");
        setDialogTitle(title);
        descriptionLabel.setText(description);
        summaryLabel.setText(summary);
        setHeaderAmount(amount);
        openDialog();
    }

    // ---- Handlers ---------------------------------------------------------

    private void onConfirm() {
        closeDialog();
        dispatcher.dispatchPosEvent(new PosEvent(confirmEventType));
    }

    private void onCancel() {
        closeDialog();
        dispatcher.dispatchPosEvent(new PosEvent(cancelEventType));
    }

    // ---- Test hooks -------------------------------------------------------

    PosButton getConfirmButtonForTest() {
        return confirmButton;
    }

    PosButton getCancelButtonForTest() {
        return cancelButton;
    }

    JLabel getDescriptionLabelForTest() {
        return descriptionLabel;
    }

    JLabel getSummaryLabelForTest() {
        return summaryLabel;
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

        // Width floor — same reasoning as VoidBasketConfirmView: pinning the body width keeps the
        // two footer buttons at a consistent visual weight across opens with different amounts.
        JComponent strut = (JComponent) Box.createRigidArea(new Dimension(BODY_MIN_WIDTH, 0));
        strut.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(strut);

        return body;
    }

    private void matchFooterButtonSizes() {
        Dimension confirmPref = confirmButton.getPreferredSize();
        Dimension cancelPref = cancelButton.getPreferredSize();
        Dimension shared = new Dimension(
                Math.max(confirmPref.width, cancelPref.width),
                Math.max(confirmPref.height, cancelPref.height));
        confirmButton.setPreferredSize(shared);
        cancelButton.setPreferredSize(shared);
    }
}
