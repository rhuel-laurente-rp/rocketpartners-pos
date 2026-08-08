package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.Box;
import javax.swing.BoxLayout;
import java.awt.GridLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Modal cash-mode-choice dialog: {@link PosDialog}-shelled, two large touch buttons stacked
 * vertically. Dialog one of the two-step cash flow — picking Exact Amount or Next Dollar
 * decides what the entry dialog pre-fills; the cashier confirms the figure there.
 *
 * <p><strong>Why this dialog exists.</strong> The old single-dialog design put the amount
 * field first with the mode buttons underneath as a shortcut. Cashiers routinely missed them
 * and hand-typed the total. Making the choice the first thing they touch turns it from an
 * easy-to-skip shortcut into an unavoidable step.</p>
 *
 * <p><strong>Choosing never tenders.</strong> Both modes lead to the entry dialog with an
 * editable field. That is what handles a customer paying with a $20 note on a $17.70 basket:
 * pick either mode, then type over the pre-filled, pre-selected value.</p>
 *
 * <p><strong>Amount context lives in the header, not the buttons.</strong> The buttons are
 * plain, big touch targets — the amount due is shown once in the dark header strip via
 * {@link PosDialog#setHeaderAmount(BigDecimal)}, and the entry dialog's pre-fill field carries
 * the actual figure. Duplicating the number on the button faces added no information but did
 * add cognitive load about which of three numbers was authoritative.</p>
 *
 * <p><strong>A whole-dollar total disables Next Dollar rather than hiding it.</strong> When
 * the ceiling of the total equals the total, the Next Dollar button is a redundant duplicate
 * of Exact Amount; disabling keeps the layout stable so the fingertip target doesn't move
 * between transactions.</p>
 *
 * <p>The footer holds Cancel alone and ESC does the same. There is no Confirm — choosing a
 * mode <em>is</em> the action. Enter is pointed at Exact Amount so the dialog remains usable
 * from the keyboard.</p>
 */
public class CashModeChoiceView extends PosDialog {

    /** Forces a minimum body width so the two mode buttons lay out consistently. */
    private static final int BODY_MIN_WIDTH = 380;

    /** Touch height of each mode button — ≥56px so a fingertip can't miss. */
    static final int MODE_BUTTON_HEIGHT = 56;

    /** Vertical gap between the two mode buttons. */
    private static final int MODE_BUTTON_GAP = 12;

    private final IPosEventDispatcher dispatcher;

    private final PosButton exactButton;
    private final PosButton nextDollarButton;
    private final PosButton cancelButton;

    private BigDecimal exactAmount = BigDecimal.ZERO;
    private BigDecimal nextDollarAmount = BigDecimal.ZERO;


    /** Horizontal gap between the two tiles. */
    private static final int MODE_TILE_GAP = 12;

    /**
     * Side length of each square tile. Derived so that {@code side * 2 + gap} equals the body
     * width — change {@link #BODY_MIN_WIDTH} and the tiles follow.
     */
    static final int MODE_TILE_SIDE = (BODY_MIN_WIDTH - MODE_TILE_GAP) / 2;

    /**
     * @param owner      the parent frame; may be {@code null}
     * @param dispatcher target for view-input events; must not be {@code null}
     */
    public CashModeChoiceView(JFrame owner, IPosEventDispatcher dispatcher) {
        super(owner, "Cash Payment");
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        this.dispatcher = dispatcher;

        this.exactButton = PosButtons.secondary("Exact Amount");
        this.exactButton.setTouchMinHeight(MODE_BUTTON_HEIGHT);
        this.nextDollarButton = PosButtons.secondary("Next Dollar");
        this.nextDollarButton.setTouchMinHeight(MODE_BUTTON_HEIGHT);
        this.cancelButton = PosButtons.danger("Cancel");

        setBody(buildBody());

        exactButton.addActionListener(
                e -> fireModeSelected(PosEventType.CASH_EXACT_PRESSED, exactAmount));
        nextDollarButton.addActionListener(
                e -> fireModeSelected(PosEventType.CASH_NEXT_DOLLAR_PRESSED, nextDollarAmount));
        cancelButton.addActionListener(e -> fireCancel());
        addSecondary(cancelButton);
        setCancelAction(this::fireCancel);

        // No primary button: choosing a mode IS the confirmation, and the footer holds Cancel
        // alone. Focusing a JButton alone is not enough to give Enter a target — a focused
        // button responds to Space; Enter activates the root pane's *default* button. Without
        // this line Enter does nothing in this dialog.
        setInitialFocus(exactButton);
        getRootPane().setDefaultButton(exactButton);
    }

    // ---- Public API called by PayWithCashViewController --------------------

    /**
     * Populates the dialog for a fresh open.
     *
     * @param exact      the transaction's grand total
     * @param nextDollar that total rounded up to the next whole dollar; must not be less than
     *                   {@code exact}
     */
    public void openFor(BigDecimal exact, BigDecimal nextDollar) {
        if (exact == null) throw new IllegalArgumentException("exact must not be null");
        if (nextDollar == null) throw new IllegalArgumentException("nextDollar must not be null");
        if (nextDollar.compareTo(exact) < 0) {
            throw new IllegalArgumentException(
                    "nextDollar (" + nextDollar + ") must not be less than exact (" + exact + ")");
        }
        this.exactAmount = exact.setScale(2, RoundingMode.HALF_UP);
        this.nextDollarAmount = nextDollar.setScale(2, RoundingMode.HALF_UP);

        // A whole-dollar total makes Next Dollar redundant. Disable rather than hide, so the
        // layout stays stable and the redundancy is visible instead of the button vanishing.
        nextDollarButton.setEnabled(exactAmount.compareTo(nextDollarAmount) != 0);

        setHeaderAmount(exactAmount);
        openDialog();
    }

    // ---- Handlers ---------------------------------------------------------

    private void fireModeSelected(PosEventType type, BigDecimal prefillAmount) {
        Map<String, Object> props = new HashMap<>();
        props.put("prefillAmount", prefillAmount);
        dispatcher.dispatchPosEvent(new PosEvent(type, props));
    }

    private void fireCancel() {
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CASH_CANCEL_PRESSED));
    }

    // ---- Test hooks -------------------------------------------------------

    PosButton getExactButtonForTest() {
        return exactButton;
    }

    PosButton getNextDollarButtonForTest() {
        return nextDollarButton;
    }

    PosButton getCancelButtonForTest() {
        return cancelButton;
    }

    /**
     * For tests: the cached exact amount, rounded to scale 2. Retained after the change to
     * plain buttons (the amount is no longer painted on the button face) so controller-level
     * assertions can still verify it was applied.
     */
    BigDecimal getExactAmountForTest() {
        return exactAmount;
    }

    /** For tests: the cached next-dollar amount. */
    BigDecimal getNextDollarAmountForTest() {
        return nextDollarAmount;
    }

    // ---- Internals --------------------------------------------------------

    private JPanel buildBody() {
        // GridLayout gives the two tiles identical cells and ignores their maximum size, so
        // squareness is enforced by constraining this row rather than the buttons: fixing the
        // row at (BODY_MIN_WIDTH x MODE_TILE_SIDE) makes each cell exactly MODE_TILE_SIDE
        // square. The buttons' own preferred size agrees, so nothing fights.
        JPanel row = new JPanel(new GridLayout(1, 2, MODE_TILE_GAP, 0));
        row.setOpaque(false);
        Dimension rowSize = new Dimension(BODY_MIN_WIDTH, MODE_TILE_SIDE);
        row.setPreferredSize(rowSize);
        row.setMinimumSize(rowSize);
        row.setMaximumSize(rowSize);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(exactButton);
        row.add(nextDollarButton);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(row);

        // No width strut needed any more: the row pins the body width itself, which also
        // removes the alignmentX mismatch a default-centred Box.createRigidArea introduced.
        return body;
    }
}
