package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Modal cash-mode-choice dialog: {@link PosDialog}-shelled. Three amount buttons in a single row
 * of equal columns — Exact Amount, Next Dollar, and Other Amount.
 *
 * <p><strong>Two ways to finish, or take a detour.</strong> Exact Amount and Next Dollar are the
 * <em>terminal</em> modes — picking one leads to a confirmation and then the receipt, with no
 * amount to key. The tile press opens a {@link TenderConfirmView} showing the figure; the cashier
 * confirms and the controller tenders (a mis-tap is recoverable via that dialog's Back). Other
 * Amount is <em>navigation</em> — it opens {@link PayWithCashView} so the cashier can key what the
 * customer actually handed over. All three share one row of equal columns — the two terminal
 * tiles first, Other Amount last — so any mode is one large fingertip target away.</p>
 *
 * <p><strong>Each tile shows the figure it will tender.</strong> Because the tiles are terminal,
 * the cashier must see the amount before committing — Exact Amount carries the grand total, Next
 * Dollar the ceiled figure. The dark header strip additionally shows the amount due once via
 * {@link PosDialog#setHeaderAmount(BigDecimal)}. Other Amount shows no figure: its amount is
 * unknown until typed.</p>
 *
 * <p><strong>Next Dollar rounds the amount due up so the cashier hands back no coins.</strong>
 * On a $17.70 basket the amount due becomes $18.00 and change is $0.00. Choosing it asserts the
 * customer handed over exactly the ceiled amount. A customer offering $20.00 instead must be
 * handled through <em>Other Amount</em>, where change is computed against the true grand total and
 * may include coins. The confirmation dialog only asks the cashier to confirm that ceiled figure —
 * do <strong>not</strong> "fix" Next Dollar back into an <em>entry</em> dialog that lets a cash
 * amount be keyed; that would undo the whole point of the one-tap design.</p>
 *
 * <p><strong>A whole-dollar total disables Next Dollar rather than hiding it.</strong> When the
 * ceiling of the total equals the total, Next Dollar and Exact Amount would produce identical
 * terminal outcomes — two enabled tiles inviting a mis-tap. Disabling (not hiding) keeps the
 * layout stable so the fingertip target doesn't move between transactions.</p>
 *
 * <p><strong>Footer.</strong> There is no Confirm — choosing a mode <em>is</em> the action.
 * Cancel occupies the primary (right) footer slot rather than sitting alone on the left, keeping
 * the shell's "primary on the right, always" invariant intact even though the button style is
 * {@code danger}. ESC and Enter both cancel — the cashier's positive action is a physical tap on
 * one of the mode buttons, and there is no affirmative-commit button for Enter to target here.
 * Cancel dispatches no tender event and leaves the transaction re-tenderable.</p>
 */
public class CashModeChoiceView extends PosDialog {

    /** Forces a minimum body width so the three mode buttons lay out consistently and large. */
    private static final int BODY_MIN_WIDTH = 576;

    /** Horizontal gap between adjacent mode tiles. */
    private static final int MODE_TILE_GAP = 12;

    /**
     * Side length of each square mode tile. Derived so that {@code side * 3 + gap * 2} equals the
     * body width — change {@link #BODY_MIN_WIDTH} and the three tiles follow.
     */
    static final int MODE_TILE_SIDE = (BODY_MIN_WIDTH - MODE_TILE_GAP * 2) / 3;

    private final IPosEventDispatcher dispatcher;

    private final PosButton exactButton;
    private final PosButton nextDollarButton;
    private final PosButton otherAmountButton;
    private final PosButton cancelButton;

    private BigDecimal exactAmount = BigDecimal.ZERO;
    private BigDecimal nextDollarAmount = BigDecimal.ZERO;

    /**
     * @param owner      the parent frame; may be {@code null}
     * @param dispatcher target for view-input events; must not be {@code null}
     */
    public CashModeChoiceView(JFrame owner, IPosEventDispatcher dispatcher) {
        super(owner, "Cash Payment");
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        this.dispatcher = dispatcher;

        this.exactButton = PosButtons.secondary("Exact Amount");
        this.exactButton.setTouchMinHeight(MODE_TILE_SIDE);
        this.nextDollarButton = PosButtons.secondary("Next Dollar");
        this.nextDollarButton.setTouchMinHeight(MODE_TILE_SIDE);
        this.otherAmountButton = PosButtons.secondary("Other Amount");
        this.otherAmountButton.setTouchMinHeight(MODE_TILE_SIDE);
        this.cancelButton = PosButtons.danger("Cancel");

        setBody(buildBody());

        // Exact and Next Dollar are terminal — the controller tenders immediately on these. The
        // prefillAmount is carried only so the journal records which mode produced the tender.
        exactButton.addActionListener(
                e -> fireModeSelected(PosEventType.CASH_EXACT_PRESSED, exactAmount));
        nextDollarButton.addActionListener(
                e -> fireModeSelected(PosEventType.CASH_NEXT_DOLLAR_PRESSED, nextDollarAmount));
        // Other Amount is navigation — it opens the entry dialog. No figure to carry.
        otherAmountButton.addActionListener(
                e -> dispatcher.dispatchPosEvent(new PosEvent(PosEventType.OTHER_CASH_AMOUNT_PRESSED)));
        cancelButton.addActionListener(e -> fireCancel());
        // Cancel is the only footer button, so it takes the right (primary) slot rather than
        // sitting alone on the left with dead space beside it. It stays styled as danger — the
        // right-slot position is about layout balance, not implying a commit; the affirmative
        // action lives on the tiles in the body.
        setPrimary(cancelButton);
        setCancelAction(this::fireCancel);

        // Enter would otherwise fire the primary button, which is now Cancel. That is safe (no
        // state has been committed at this point) and matches ESC. Initial focus lands on Exact
        // Amount so Space still selects the most common choice.
        setInitialFocus(exactButton);
    }

    // ---- Public API called by PayWithCashViewController --------------------

    /**
     * Populates the dialog for a fresh open and shows it.
     *
     * @param exact      the transaction's grand total
     * @param nextDollar that total rounded up to the next whole dollar; must not be less than
     *                   {@code exact}
     */
    public void openFor(BigDecimal exact, BigDecimal nextDollar) {
        applyAmounts(exact, nextDollar);
        openDialog();
    }

    /**
     * Primes the tile figures, header amount, and Next-Dollar enablement without showing the
     * dialog. Split out of {@link #openFor} so a snapshot harness can render the primed body
     * without entering the modal event loop.
     */
    void applyAmounts(BigDecimal exact, BigDecimal nextDollar) {
        if (exact == null) throw new IllegalArgumentException("exact must not be null");
        if (nextDollar == null) throw new IllegalArgumentException("nextDollar must not be null");
        if (nextDollar.compareTo(exact) < 0) {
            throw new IllegalArgumentException(
                    "nextDollar (" + nextDollar + ") must not be less than exact (" + exact + ")");
        }
        this.exactAmount = exact.setScale(2, RoundingMode.HALF_UP);
        this.nextDollarAmount = nextDollar.setScale(2, RoundingMode.HALF_UP);

        // Each tile shows the amount it will tender — the cashier sees the figure before
        // committing to a terminal one-tap action.
        exactButton.setText(tileLabel("Exact Amount", exactAmount));
        nextDollarButton.setText(tileLabel("Next Dollar", nextDollarAmount));

        // A whole-dollar total makes Next Dollar redundant with Exact Amount — and now that both
        // tender immediately, two enabled tiles with identical terminal outcomes invite a
        // mis-tap. Disable rather than hide so the layout stays stable.
        nextDollarButton.setEnabled(exactAmount.compareTo(nextDollarAmount) != 0);

        setHeaderAmount(exactAmount);
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

    private static String tileLabel(String name, BigDecimal amount) {
        return "<html><center>" + name + "<br><b>" + PosTheme.money(amount) + "</b></center></html>";
    }

    // ---- Test hooks -------------------------------------------------------

    PosButton getExactButtonForTest() {
        return exactButton;
    }

    PosButton getNextDollarButtonForTest() {
        return nextDollarButton;
    }

    PosButton getOtherAmountButtonForTest() {
        return otherAmountButton;
    }

    PosButton getCancelButtonForTest() {
        return cancelButton;
    }

    /** For tests: the cached exact amount, rounded to scale 2. */
    BigDecimal getExactAmountForTest() {
        return exactAmount;
    }

    /** For tests: the cached next-dollar amount. */
    BigDecimal getNextDollarAmountForTest() {
        return nextDollarAmount;
    }

    // ---- Internals --------------------------------------------------------

    private JPanel buildBody() {
        // All three cash modes share one row of three equal columns. GridLayout gives identical
        // cells and ignores each button's maximum size, so the cell size is enforced by fixing the
        // row at (BODY_MIN_WIDTH x MODE_TILE_SIDE): each of the three cells comes out MODE_TILE_SIDE
        // square and spans the widened body. Exact and Next Dollar are terminal one-tap tenders;
        // Other Amount opens the entry dialog — the distinction is behavioural, the row just keeps
        // the three one large fingertip apart.
        JPanel row = new JPanel(new GridLayout(1, 3, MODE_TILE_GAP, 0));
        row.setOpaque(false);
        Dimension rowSize = new Dimension(BODY_MIN_WIDTH, MODE_TILE_SIDE);
        row.setPreferredSize(rowSize);
        row.setMinimumSize(rowSize);
        row.setMaximumSize(rowSize);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(exactButton);
        row.add(nextDollarButton);
        row.add(otherAmountButton);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(row);

        return body;
    }
}
