package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JScrollBar;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The customer-facing basket screen: a dumb Swing renderer laid out as a two-column proportional
 * POS shell.
 *
 * <p>The window is a fixed 1512×982 non-resizable surface, so the layout is proportional, not
 * responsive: every split is an exact fraction via {@link ProportionalLayout}. Four nesting
 * levels — content width 30/70, each column 80/20, and the bottom-right row 60/40:</p>
 * <ul>
 *   <li><strong>Left 30%.</strong> Top 80%: the <strong>Basket</strong> — scan-bar mount point
 *       above the line-item list (or an empty-state prompt). Bottom 20%: the <strong>Summary</strong>
 *       tape — Subtotal, Discount, Tax, then the large TOTAL.</li>
 *   <li><strong>Right 70%.</strong> Top 80%: <strong>Quick Add</strong> — a searchable, sortable,
 *       paged grid of tiles over the whole pricebook (see {@link QuickAddPanel}), each dispatching
 *       a {@link PosEventType#QUICK_ADD_PRESSED} event carrying its bound UPC. Bottom 20%: split
 *       60/40 into <strong>Actions</strong> — one row of five tall buttons: Void Basket, Void Line,
 *       Change Qty, Discount, Total — and <strong>Payment</strong> — one row of three: Pay Cash,
 *       Pay Debit, Pay Credit (disabled until Total is pressed).</li>
 * </ul>
 *
 * <p>All palette and type tokens live in {@link PosTheme}; button variants in {@link PosButtons}.
 * This class holds no colour or font literals — if a new shade is needed, it belongs on the
 * theme first.</p>
 *
 * <p>Per {@code docs/Phase 1/event-flow.md}, a {@code *View} class holds no business logic and
 * has no {@code TransactionService} reference. Density mode, highlight flashes, hover state, and
 * scroll position are pure view state kept here — the domain doesn't know about them and
 * shouldn't. Duplicate-scan merging is a {@link com.rocketpartners.onboarding.commons.model.Transaction}
 * concern; this view only <em>animates</em> the quantity change the domain has already made.</p>
 */
public class CustomerView extends JFrame {

    // ---- Window sizing -----------------------------------------------------
    // The POS terminal is a fixed 1512×982 register display. The window opens at exactly that
    // size and is non-resizable — the cashier can't drag it into a shape the layout wasn't
    // designed for. Because the surface never changes size, the shell is *proportional*, not
    // responsive: every split is an exact fraction of the available space (see
    // {@link ProportionalLayout}), with no breakpoints and no reflow. GridBagLayout weights can't
    // express that — they distribute only the surplus left after preferred sizes are met — so the
    // nesting here is ProportionalLayout the whole way down.
    private static final int WINDOW_WIDTH = 1512;
    private static final int WINDOW_HEIGHT = 982;

    /** Padding inside the content area, between the window edge and the outer columns. */
    private static final int OUTER_PAD = 12;
    /** Gap between adjacent cards (columns, rows, and the actions/payment split). */
    private static final int CARD_GAP = 12;


    // ---- Proportional split fractions --------------------------------------
    /** Left column (basket + summary) share of the content width. */
    private static final float LEFT_FRACTION = 0.30f;
    /** Right column (quick add + actions/payment) share of the content width. */
    private static final float RIGHT_FRACTION = 0.70f;
    /** Basket / Quick Add share of a column's height; the summary / bottom row take the rest. */
    private static final float TOP_ROW_FRACTION = 0.80f;
    private static final float BOTTOM_ROW_FRACTION = 0.20f;
    /** Actions share of the bottom-right row; Payment takes the rest. Payment is the terminal
     *  action and carries more visual weight than a single edit control, so tender buttons come
     *  out wider than action buttons at this split. */
    private static final float ACTIONS_FRACTION = 0.60f;
    private static final float PAYMENT_FRACTION = 0.40f;

    /** Bottom padding beneath the actions/payment row, matched to {@link #OUTER_PAD} so the
     *  window inset reads as uniform. A control flush to the panel edge is measurably harder to
     *  hit on a touchscreen — the bezel interferes with the finger's approach angle. */
    private static final int BOTTOM_ROW_PAD = 12;

    // ---- Density animation -------------------------------------------------
    /** Duration of the row-height glide between Comfortable and Compact. */
    private static final int DENSITY_ANIM_MS = 150;
    /** Frame interval for the density animation. 16ms ≈ 60fps. */
    private static final int DENSITY_FRAME_MS = 16;

    // ---- Flash overlay -----------------------------------------------------
    /** Duration of the newest-scan green flash. */
    private static final int FLASH_MS = 400;
    /** Peak green-tint alpha for the flash (out of 255). ~12% of 255 ≈ 30. */
    private static final int FLASH_PEAK_ALPHA = 30;
    /** Frame interval for the flash fade. */
    private static final int FLASH_FRAME_MS = 16;

    private final IPosEventDispatcher dispatcher;

    private final DefaultListModel<LineItem> basketModel = new DefaultListModel<>();
    private final JList<LineItem> basketList = new JList<>(basketModel);
    private final BasketCellRenderer basketRenderer = new BasketCellRenderer();
    private final FlashLayerPanel basketLayer = new FlashLayerPanel();

    private final CardLayout basketCards = new CardLayout();
    private final JPanel basketCenter = new JPanel(basketCards);

    // Summary tape — Subtotal → Discount → Tax → Total, matching ReceiptFormatter's on-screen
    // order. Built once, mutated in-place; no allocation on the render path.
    private final JLabel subtotalLabel = new JLabel("Subtotal");
    private final JLabel subtotalValue = new JLabel("$0.00", SwingConstants.RIGHT);
    private final JLabel discountLabel = new JLabel("Discount");
    private final JLabel discountValue = new JLabel("$0.00", SwingConstants.RIGHT);
    private final JLabel taxLabel = new JLabel("Tax");
    private final JLabel taxValue = new JLabel("$0.00", SwingConstants.RIGHT);
    private final JLabel totalLabel = new JLabel("TOTAL");
    private final JLabel totalValue = new JLabel("$0.00", SwingConstants.RIGHT);
    private JPanel summaryTape;

    private final JLabel amountDueValue = new JLabel("$0.00", SwingConstants.RIGHT);
    private final JLabel statusPill = new JLabel("OPEN", SwingConstants.CENTER);
    private final JournalStatusIndicator journalIndicator = new JournalStatusIndicator();

    /** The Quick Add card body: search + sort + paged tile grid over the whole pricebook. */
    private QuickAddPanel quickAddPanel;
    // "Change Qty" rather than the dialog's full "Change Quantity": five buttons now share the
    // actions strip, so the label is shortened to fit its narrower target while the dialog title
    // stays "Change Quantity".
    private final PosButton changeQtyButton = PosButtons.secondary("Change Qty");
    private final PosButton voidLineButton = PosButtons.secondary("Void Line");
    // Discount lives in the actions row but is disabled: applying a discount mid-transaction is a
    // domain change (see PosEventType#DISCOUNT_PRESSED) scheduled for feature/in-progress-discounts.
    // The button and its listener are wired so the slot is real; it never fires while disabled.
    private final PosButton discountButton = PosButtons.secondary("Discount");
    private final PosButton voidBasketButton = PosButtons.danger("Void Basket");
    private final PosButton totalButton = PosButtons.primary("Total");

    private final PosButton payCashButton = PosButtons.tender("Pay Cash", PosTheme.GO);
    private final PosButton payDebitButton = PosButtons.tender("Pay Debit", PosTheme.CARD_DEBIT);
    private final PosButton payCreditButton = PosButtons.tender("Pay Credit", PosTheme.CARD_CREDIT);

    /**
     * Whether basket mutation is currently permitted. Tracked explicitly rather than read off a
     * button, because both selection-dependent buttons vary independently of it and no single
     * button's enabled state is a sound proxy for the transaction phase.
     */
    private boolean basketInputEnabled = true;

    /**
     * Whether lifecycle-ending actions (today: void basket) are currently permitted. Tracked
     * separately from {@link #basketInputEnabled} because the two rules disagree at TOTALED:
     * basket input is off (no more scans, quantity edits, or line voids), but voiding the whole
     * transaction is still legal in the domain and must remain reachable. Additionally gated on
     * the basket holding at least one non-voided line — nothing to discard, no confirmation
     * dialog worth opening.
     */
    private boolean lifecycleInputEnabled = true;

    /** Sum of non-voided line-item quantities from the last render. Used to gate Void basket. */
    private int lastNonVoidedQuantitySum;

    /** Mount point for the {@link ScannerView} at the top of the Basket column. */
    private final JPanel basketNorthSlot = new JPanel(new BorderLayout());

    /** The bottom-right payment card (Pay Cash + Debit/Credit). Kept so tests and the snapshot
     *  harness can render it standalone without cropping the whole frame. Assigned during layout. */
    private JPanel paymentPanel;

    // ---- Layout containers (test hooks) ------------------------------------
    // The proportional split containers, retained so layout tests can assert the exact 30/70,
    // 80/20, and 70/30 divisions without walking the whole component tree.
    private JPanel columnsRow;   // horizontal: left 30% | right 70%
    private JPanel leftColumn;   // vertical: basket 80% / summary 20%
    private JPanel rightColumn;  // vertical: quick add 80% / bottom row 20%
    private JPanel bottomRow;    // horizontal: actions 70% | payment 30%
    private JPanel actionsPanel; // actions card (four buttons + Total)
    private JPanel cardTenderRow; // Pay Debit | Pay Credit split

    /**
     * Snapshot of quantities keyed by {@link LineItem} identity from the last render. Used to
     * decide whether an incoming update represents a new line ("added") or an existing line's
     * quantity bumping upward ("merged"). Presentation state, not domain state — the domain
     * already merged; the view just needs to remember what it saw last time so it can animate
     * the change.
     */
    private final Map<LineItem, Integer> previousQuantities = new LinkedHashMap<>();

    /** The density animation timer, if running. Nulled after completion. */
    private Timer densityTimer;
    private int densityFromHeight;
    private int densityToHeight;
    private long densityStartNanos;

    /** The flash timer, if running. Nulled after completion. */
    private Timer flashTimer;
    private int flashIndex = -1;
    private long flashStartNanos;

    /**
     * @param title         window title (typically {@code "Rocket POS — <store> lane <n>"})
     * @param quickAddItems items to draw quick-add buttons for; may be empty but not {@code null}
     * @param dispatcher    target for view-input events; must not be {@code null}
     */
    public CustomerView(String title, List<Item> quickAddItems, IPosEventDispatcher dispatcher) {
        super(title);
        if (quickAddItems == null) throw new IllegalArgumentException("quickAddItems must not be null");
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        this.dispatcher = dispatcher;

        // Fixed register-display size, non-resizable. No MAXIMIZED_BOTH: the proportional shell
        // is designed against exactly this surface, so the window is pinned to it rather than
        // stretched to whatever the WM hands us.
        setPreferredSize(new Dimension(WINDOW_WIDTH, WINDOW_HEIGHT));
        setSize(new Dimension(WINDOW_WIDTH, WINDOW_HEIGHT));
        setResizable(false);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(PosTheme.PAPER);
        root.add(buildHeader(title), BorderLayout.NORTH);
        root.add(buildMainArea(quickAddItems), BorderLayout.CENTER);
        setContentPane(root);

        refreshTotalButton();
        refreshStatusPill();
        setLocationRelativeTo(null);
    }

    // ---- Public API called by CustomerViewController -----------------------

    /**
     * Replaces the basket contents and running-total display. Breakdown rows are zeroed; use
     * {@link #updateBasket(List, BigDecimal, BigDecimal, BigDecimal, BigDecimal)} when the
     * controller has discount and tax figures to show.
     */
    public void updateBasket(List<LineItem> lines, BigDecimal runningTotal) {
        updateBasket(lines, runningTotal, BigDecimal.ZERO, BigDecimal.ZERO, runningTotal);
    }

    /** Replaces the basket contents and the full summary tape. */
    public void updateBasket(List<LineItem> lines, BigDecimal subtotal, BigDecimal discount,
                             BigDecimal tax, BigDecimal total) {
        if (lines == null) throw new IllegalArgumentException("lines must not be null");
        if (subtotal == null) throw new IllegalArgumentException("subtotal must not be null");
        if (discount == null) throw new IllegalArgumentException("discount must not be null");
        if (tax == null) throw new IllegalArgumentException("tax must not be null");
        if (total == null) throw new IllegalArgumentException("total must not be null");

        // Diff against the previous quantities snapshot BEFORE mutating the model so we can
        // decide which row (if any) to flash. "Newest scan" is the last row whose quantity is
        // now higher than it was last time we rendered, or the last brand-new line.
        int flashRow = -1;
        boolean flashIsBump = false;
        for (int i = 0; i < lines.size(); i++) {
            LineItem li = lines.get(i);
            if (li.isVoided()) continue;
            Integer prev = previousQuantities.get(li);
            if (prev == null) {
                flashRow = i;
                flashIsBump = false;
            } else if (li.getQuantity() > prev) {
                flashRow = i;
                flashIsBump = true;
            }
        }

        // Rebuilding the model drops the selection; the "grow → follow, hold → clamp" logic
        // stops the selection-dependent buttons from flickering off on every totals refresh.
        int previousIndex = basketList.getSelectedIndex();
        int previousSize = basketModel.getSize();

        basketModel.clear();
        for (LineItem li : lines) {
            basketModel.addElement(li);
        }
        basketCards.show(basketCenter, lines.isEmpty() ? "empty" : "list");

        if (!lines.isEmpty()) {
            int restored = lines.size() > previousSize
                    ? lines.size() - 1
                    : Math.min(previousIndex, lines.size() - 1);
            if (restored >= 0) {
                basketList.setSelectedIndex(restored);
                basketList.ensureIndexIsVisible(restored);
            }
        }

        // Density derives strictly from item count. Voided lines still occupy screen space, so
        // they count — the cashier still has to scroll past them.
        BasketCellRenderer.Density target = BasketCellRenderer.densityFor(lines.size());
        if (target != basketRenderer.getDensity()) {
            animateDensityTransition(target);
        }

        // Snapshot the non-voided quantity sum first — the vertical summary shows the item count
        // beside the Subtotal label, and it must match the value stored on this render.
        int qtySum = 0;
        for (LineItem li : lines) {
            if (!li.isVoided()) qtySum += li.getQuantity();
        }
        lastNonVoidedQuantitySum = qtySum;

        renderVerticalSummary(subtotal, discount, tax, total);
        totalValue.setText(PosTheme.money(total));
        amountDueValue.setText(PosTheme.money(total));

        if (flashRow >= 0) {
            startFlash(flashRow, flashIsBump);
        }

        // Snapshot AFTER the flash decision so the next call diffs against the state we just
        // rendered. Identity-keyed so a merged bump on the same LineItem is detected as an
        // increase, not a new arrival.
        previousQuantities.clear();
        for (LineItem li : lines) {
            previousQuantities.put(li, li.getQuantity());
        }

        refreshSelectionDependentButtons();
        refreshVoidBasketButton();
        refreshTotalButton();
    }

    /**
     * @return sum of quantities across non-voided line items from the last render. Note this is
     *         the sum of quantities, not the line count — a single line at quantity 12 counts as
     *         twelve items to re-scan.
     */
    public int getBasketItemCount() {
        return lastNonVoidedQuantitySum;
    }

    /**
     * Enables or disables the basket-input controls — quick-add tiles, Change qty, Void line,
     * and Total. Deliberately does <em>not</em> touch the Void basket button: the domain state
     * machine legalises {@code voidBasket()} in both {@code IN_PROGRESS} and {@code TOTALED},
     * so grouping Void basket with the mutation controls disables it exactly where cashiers
     * most need it (a customer changing their mind at the card reader after Total was pressed).
     * Void basket has its own gate via {@link #setLifecycleInputEnabled(boolean)}.
     */
    public void setBasketInputEnabled(boolean enabled) {
        basketInputEnabled = enabled;
        if (quickAddPanel != null) quickAddPanel.setTilesEnabled(enabled);
        refreshTotalButton();
        refreshSelectionDependentButtons();
        refreshStatusPill();
    }

    /**
     * Enables or disables lifecycle-ending controls — today, only Void basket. Kept separate
     * from {@link #setBasketInputEnabled(boolean)} so the two rules cannot drift back together:
     * disabling basket input at TOTALED must leave Void basket alone, because the domain still
     * permits it. Callers should pass {@code false} only for terminal states ({@code PAID},
     * {@code VOIDED}), matching {@link com.rocketpartners.onboarding.commons.model.Transaction#voidBasket()}.
     *
     * <p>Void basket is additionally hidden when the basket holds no non-voided line items —
     * there is nothing to discard, and it keeps the confirmation dialog from ever opening with
     * an empty summary. That secondary gate is applied by {@link #refreshVoidBasketButton()}
     * and does not require callers to know the item count.</p>
     */
    public void setLifecycleInputEnabled(boolean enabled) {
        lifecycleInputEnabled = enabled;
        refreshVoidBasketButton();
        refreshStatusPill();
    }

    private void refreshVoidBasketButton() {
        // Void basket is legal in IN_PROGRESS and TOTALED (the caller sets lifecycleInputEnabled
        // accordingly). It also requires at least one non-voided line — an all-voided basket has
        // nothing to discard, and opening the confirmation dialog on an empty summary is
        // meaningless. Both gates together produce the final enabled state.
        voidBasketButton.setEnabled(lifecycleInputEnabled && lastNonVoidedQuantitySum > 0);
    }

    private void refreshTotalButton() {
        // Two gates, same shape as refreshVoidBasketButton: the phase gate (basketInputEnabled
        // is off in TOTALED/PAID/VOIDED) and the content gate (nothing to total when every line
        // is voided or the basket is empty). Either one closed disables the button.
        totalButton.setEnabled(basketInputEnabled && lastNonVoidedQuantitySum > 0);
    }

    private void refreshSelectionDependentButtons() {
        LineItem sel = basketList.getSelectedValue();
        boolean actionable = basketInputEnabled && sel != null && !sel.isVoided();
        changeQtyButton.setEnabled(actionable);
        voidLineButton.setEnabled(actionable);
    }

    /** Enables or disables the tender controls (Pay Cash / Debit / Credit). */
    public void setTenderInputEnabled(boolean enabled) {
        payCashButton.setEnabled(enabled);
        payDebitButton.setEnabled(enabled);
        payCreditButton.setEnabled(enabled);
        amountDueValue.setForeground(enabled ? PosTheme.INK : PosTheme.MUTED);
        refreshStatusPill();
    }

    /**
     * Derives the header pill from the phase flags.
     *
     * <p>OPEN is the state where basket input is accepted — an empty fresh transaction still
     * counts. Using {@code totalButton.isEnabled()} as the OPEN proxy would fall through to
     * LOCKED on first run (empty basket = no Total = "terminal state" by the derived logic),
     * which reads to the cashier as "the lane is dead." {@link #basketInputEnabled} tracks the
     * phase directly.</p>
     */
    private void refreshStatusPill() {
        boolean tenderOn = payCashButton.isEnabled();
        if (tenderOn) {
            statusPill.setText("AWAITING PAYMENT");
            statusPill.setForeground(Color.WHITE);
            statusPill.setBackground(PosTheme.LIVE);
        } else if (basketInputEnabled) {
            statusPill.setText("OPEN");
            statusPill.setForeground(new Color(0xC9, 0xD1, 0xD8));
            statusPill.setBackground(new Color(0x2A, 0x31, 0x39));
        } else {
            statusPill.setText("LOCKED");
            statusPill.setForeground(new Color(0x8A, 0x92, 0x9A));
            statusPill.setBackground(new Color(0x22, 0x28, 0x2E));
        }
    }

    /** @return the line item currently selected in the basket list, or {@code null} if none */
    public LineItem getSelectedLineItem() {
        return basketList.getSelectedValue();
    }

    /** @return {@code true} if the Change Qty button is currently enabled */
    public boolean isChangeQtyEnabled() {
        return changeQtyButton.isEnabled();
    }

    /** @return {@code true} if the Void Line button is currently enabled */
    public boolean isVoidLineEnabled() {
        return voidLineButton.isEnabled();
    }

    /** @return {@code true} if the Void basket button is currently enabled */
    public boolean isVoidBasketEnabled() {
        return voidBasketButton.isEnabled();
    }

    /** @return {@code true} if the Total button is currently enabled */
    public boolean isTotalEnabled() {
        return totalButton.isEnabled();
    }

    /** For tests: whether the Discount button is enabled. It stays disabled until the
     *  in-progress-discount feature lands (see {@link PosEventType#DISCOUNT_PRESSED}). */
    boolean isDiscountEnabledForTest() {
        return discountButton.isEnabled();
    }

    /** For tests: whether the three tender buttons (cash/debit/credit) are enabled. */
    boolean isTenderEnabledForTest() {
        return payCashButton.isEnabled() && payDebitButton.isEnabled()
                && payCreditButton.isEnabled();
    }

    /**
     * Dismisses the Quick Add search keyboard, if it's open. Called by the controller when an
     * item is added — a successful scan or a tapped tile means the cashier no longer needs the
     * search fallback, and leaving the keyboard up would be stale UI. Leaves the search text and
     * grid filter untouched. No-op if the keyboard is already hidden.
     */
    public void dismissSearchKeyboard() {
        if (quickAddPanel != null) quickAddPanel.hideKeyboard();
    }

    /**
     * Installs the given component as the scan bar at the top of the Basket column. Idempotent
     * — a subsequent call replaces the previous scan bar.
     */
    public void installScanBar(JComponent scanBar) {
        if (scanBar == null) throw new IllegalArgumentException("scanBar must not be null");
        basketNorthSlot.removeAll();
        basketNorthSlot.add(scanBar, BorderLayout.CENTER);
        basketNorthSlot.revalidate();
        basketNorthSlot.repaint();
    }

    // ---- Test hooks --------------------------------------------------------

    /** For tests: current renderer density. */
    BasketCellRenderer.Density getBasketDensity() {
        return basketRenderer.getDensity();
    }

    /** For tests: current row height of the basket list. */
    int getBasketRowHeight() {
        return basketList.getFixedCellHeight();
    }

    /** For tests: the active flash row, or -1 if none. */
    int getFlashRowForTest() {
        return flashIndex;
    }

    /** For tests: the currently-hovered row, or -1 if none. */
    int getHoverRowForTest() {
        return basketRenderer.getHoverIndex();
    }

    /** For tests: nudge the hover to a given index (simulating a MouseMotionListener event). */
    void setHoverRowForTest(int index) {
        setHoverRow(index);
    }

    /** For tests: the underlying basket list. */
    JList<LineItem> getBasketListForTest() {
        return basketList;
    }

    /** For tests: the three tender buttons in cash / debit / credit order. */
    PosButton[] getTenderButtonsForTest() {
        return new PosButton[]{payCashButton, payDebitButton, payCreditButton};
    }

    /** For tests/snapshots: the five action buttons in on-screen (left-to-right) order:
     *  Void Basket, Void Line, Change Qty, Discount, Total. */
    PosButton[] getActionButtonsForTest() {
        return new PosButton[]{voidBasketButton, voidLineButton, changeQtyButton,
                discountButton, totalButton};
    }

    // Summary tape test hooks — expose the four labels and the tape container so tests can
    // assert order, values, colours, and layout-stability without reflecting on private fields.
    JLabel getSubtotalLabelForTest() { return subtotalLabel; }
    JLabel getSubtotalValueForTest() { return subtotalValue; }
    JLabel getDiscountLabelForTest() { return discountLabel; }
    JLabel getDiscountValueForTest() { return discountValue; }
    JLabel getTaxLabelForTest() { return taxLabel; }
    JLabel getTaxValueForTest() { return taxValue; }
    JLabel getTotalLabelForTest() { return totalLabel; }
    JLabel getTotalValueForTest() { return totalValue; }
    JLabel getAmountDueValueForTest() { return amountDueValue; }
    JPanel getSummaryTapeForTest() { return summaryTape; }

    /**
     * For the snapshot harness: the payment card (Pay Cash + Debit/Credit), so a caller can
     * render it standalone without cropping by pixel proportion. Named for backward
     * compatibility with the snapshot tool that predates the two-column layout.
     */
    JPanel getTenderColumnForTest() {
        return paymentPanel;
    }

    // ---- Layout-split test hooks -------------------------------------------
    // The proportional containers, so layout tests can assert the 30/70, 80/20, and 70/30
    // divisions directly rather than walking the tree.
    JPanel getColumnsRowForTest() { return columnsRow; }
    JPanel getLeftColumnForTest() { return leftColumn; }
    JPanel getRightColumnForTest() { return rightColumn; }
    JPanel getBottomRowForTest() { return bottomRow; }
    JPanel getActionsPanelForTest() { return actionsPanel; }
    JPanel getPaymentPanelForTest() { return paymentPanel; }
    JPanel getCardTenderRowForTest() { return cardTenderRow; }
    QuickAddPanel getQuickAddPanelForTest() { return quickAddPanel; }

    // ---- Layout helpers ----------------------------------------------------

    private JPanel buildHeader(String title) {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(PosTheme.INK);
        header.setBorder(BorderFactory.createEmptyBorder(14, 20, 14, 20));

        JLabel label = new JLabel(title);
        label.setFont(PosTheme.base(Font.BOLD, PosTheme.BUTTON));
        label.setForeground(Color.WHITE);
        header.add(label, BorderLayout.WEST);

        statusPill.setFont(PosTheme.eyebrow());
        statusPill.setOpaque(true);
        statusPill.setBorder(BorderFactory.createEmptyBorder(5, 14, 5, 14));

        JPanel rightSide = new JPanel();
        rightSide.setOpaque(false);
        rightSide.setLayout(new BoxLayout(rightSide, BoxLayout.X_AXIS));
        rightSide.add(journalIndicator);
        rightSide.add(Box.createHorizontalStrut(12));
        rightSide.add(statusPill);
        header.add(rightSide, BorderLayout.EAST);
        return header;
    }

    /**
     * Updates the header's journal connection indicator. Safe to call from any thread — the
     * update is marshaled onto the Swing EDT if the caller isn't already on it.
     *
     * @param connected {@code true} when the {@link com.rocketpartners.onboarding.possystem.component.RemoteJournal}
     *                  has an open socket to the virtual journal server; {@code false} otherwise
     */
    public void setJournalConnected(boolean connected) {
        if (SwingUtilities.isEventDispatchThread()) {
            journalIndicator.setConnected(connected);
        } else {
            SwingUtilities.invokeLater(() -> journalIndicator.setConnected(connected));
        }
    }

    /** For tests: whether the indicator currently reads as connected. */
    boolean isJournalConnectedForTest() {
        return journalIndicator.isConnected();
    }

    /**
     * The two-column proportional shell. Four nesting levels, every split an exact fraction via
     * {@link ProportionalLayout}:
     * <pre>
     *   columnsRow (H)  ── left 30% | right 70%
     *     leftColumn (V)  ── basket 80% / summary 20%
     *     rightColumn (V) ── quick add 80% / bottomRow 20%
     *       bottomRow (H)   ── actions 70% | payment 30%
     * </pre>
     * Inter-card gutters are applied as {@link #CARD_GAP} borders on the leading child of each
     * split, so the fraction ProportionalLayout measures (which includes the child's border) is
     * still the exact split the sketch calls for.
     */
    private JPanel buildMainArea(List<Item> quickAddItems) {
        columnsRow = new JPanel(new ProportionalLayout(ProportionalLayout.HORIZONTAL));
        columnsRow.setBackground(PosTheme.PAPER);
        columnsRow.setBorder(BorderFactory.createEmptyBorder(OUTER_PAD, OUTER_PAD, OUTER_PAD, OUTER_PAD));

        JPanel left = buildLeftColumn();
        left.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, CARD_GAP));
        columnsRow.add(left, LEFT_FRACTION);
        columnsRow.add(buildRightColumn(quickAddItems), RIGHT_FRACTION);
        return columnsRow;
    }

    private JPanel buildLeftColumn() {
        leftColumn = new JPanel(new ProportionalLayout(ProportionalLayout.VERTICAL));
        leftColumn.setOpaque(false);

        JPanel basket = buildBasketCard();
        basket.setBorder(BorderFactory.createEmptyBorder(0, 0, CARD_GAP, 0));
        leftColumn.add(basket, TOP_ROW_FRACTION);
        leftColumn.add(buildSummaryCard(), BOTTOM_ROW_FRACTION);
        return leftColumn;
    }

    private JPanel buildRightColumn(List<Item> quickAddItems) {
        rightColumn = new JPanel(new ProportionalLayout(ProportionalLayout.VERTICAL));
        rightColumn.setOpaque(false);

        JPanel quickAdd = buildQuickAddCard(quickAddItems);
        quickAdd.setBorder(BorderFactory.createEmptyBorder(0, 0, CARD_GAP, 0));
        rightColumn.add(quickAdd, TOP_ROW_FRACTION);
        rightColumn.add(buildBottomRow(), BOTTOM_ROW_FRACTION);
        return rightColumn;
    }

    private JPanel buildBottomRow() {
        bottomRow = new JPanel(new ProportionalLayout(ProportionalLayout.HORIZONTAL));
        bottomRow.setOpaque(false);
        // Bottom padding so the tender/action strip doesn't sit flush against the window edge —
        // ProportionalLayout honours the container insets, so this simply shortens the row's
        // usable height by BOTTOM_ROW_PAD without disturbing the 60/40 horizontal split.
        bottomRow.setBorder(BorderFactory.createEmptyBorder(0, 0, BOTTOM_ROW_PAD, 0));

        JPanel actions = buildActionsCard();
        actions.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, CARD_GAP));
        bottomRow.add(actions, ACTIONS_FRACTION);
        bottomRow.add(buildPaymentCard(), PAYMENT_FRACTION);
        return bottomRow;
    }

    private JPanel buildQuickAddCard(List<Item> quickAddItems) {
        // The grid spans the whole pricebook, paged, with its own search + sort. Each tile press
        // dispatches QUICK_ADD_PRESSED carrying the bound UPC — the panel stays a dumb view.
        quickAddPanel = new QuickAddPanel(quickAddItems, item -> {
            Map<String, Object> props = new HashMap<>();
            props.put("upc", item.getUpc());
            dispatcher.dispatchPosEvent(new PosEvent(PosEventType.QUICK_ADD_PRESSED, props));
        });
        return PosTheme.card("Quick add", quickAddPanel);
    }

    private JPanel buildBasketCard() {
        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(PosTheme.SURFACE);

        basketNorthSlot.setOpaque(false);
        basketNorthSlot.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, PosTheme.RULE),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        body.add(basketNorthSlot, BorderLayout.NORTH);

        basketList.setCellRenderer(basketRenderer);
        basketList.setFixedCellHeight(BasketCellRenderer.COMFORTABLE_ROW_HEIGHT);
        basketList.setBackground(PosTheme.SURFACE);
        basketList.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        installHoverTracking();

        JScrollPane listScroll = new JScrollPane(basketList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.getViewport().setBackground(PosTheme.SURFACE);
        listScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        listScroll.getVerticalScrollBar().setUnitIncrement(16);
        // Hide the scrollbar entirely when everything fits — it's overlaid so it doesn't steal
        // row width when it does appear.
        listScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        styleThinScrollBar(listScroll.getVerticalScrollBar());

        // Wrap the scroll pane in a layered panel so the flash overlay can be painted on top of
        // just the flashed row bounds without touching the renderer or the list background.
        basketLayer.setSource(basketList);
        basketLayer.setLayout(new BorderLayout());
        basketLayer.add(listScroll, BorderLayout.CENTER);

        // The "list" card carries a fixed column-header row above the scrolling rows, aligned to
        // the same column geometry the renderer uses. The empty-state card has no header.
        JPanel listCard = new JPanel(new BorderLayout());
        listCard.setBackground(PosTheme.SURFACE);
        listCard.add(buildBasketColumnHeader(), BorderLayout.NORTH);
        listCard.add(basketLayer, BorderLayout.CENTER);

        basketCenter.setBackground(PosTheme.SURFACE);
        basketCenter.add(listCard, "list");
        basketCenter.add(buildEmptyState(), "empty");
        basketCards.show(basketCenter, "empty");
        body.add(basketCenter, BorderLayout.CENTER);

        // The summary tape and the basket actions no longer live under the list — they moved to
        // the left column's bottom cell and the bottom-right actions card respectively. The list
        // now owns the full height of the basket card.
        basketList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) refreshSelectionDependentButtons();
        });
        return PosTheme.card("Basket", body);
    }

    /**
     * The basket table's column-header row: {@code Item} left, {@code Price / Qty / Total} right,
     * all EYEBROW. Built from {@link BasketCellRenderer#numericColumns} at the shared fixed widths
     * and the same {@link BasketCellRenderer#ITEM_INSET_LEFT}/{@code RIGHT} insets so the headers
     * sit directly above the values the renderer paints.
     */
    private JPanel buildBasketColumnHeader() {
        JLabel item = headerEyebrow("Item", SwingConstants.LEFT);
        JLabel priceHead = headerEyebrow("Price", SwingConstants.RIGHT);
        JLabel qtyHead = headerEyebrow("Qty", SwingConstants.CENTER);
        JLabel totalHead = headerEyebrow("Total", SwingConstants.RIGHT);

        JPanel header = new JPanel(new BorderLayout(BasketCellRenderer.COL_GAP, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, PosTheme.RULE),
                BorderFactory.createEmptyBorder(8, BasketCellRenderer.ITEM_INSET_LEFT,
                        8, BasketCellRenderer.ITEM_INSET_RIGHT)));
        header.add(item, BorderLayout.CENTER);
        header.add(BasketCellRenderer.numericColumns(priceHead, qtyHead, totalHead), BorderLayout.EAST);
        return header;
    }

    private static JLabel headerEyebrow(String text, int alignment) {
        JLabel label = new JLabel(text, alignment);
        label.setFont(PosTheme.eyebrow());
        label.setForeground(PosTheme.MUTED);
        return label;
    }

    private JPanel buildEmptyState() {
        JPanel empty = new JPanel(new GridBagLayout());
        empty.setBackground(PosTheme.SURFACE);

        JPanel stack = new JPanel();
        stack.setOpaque(false);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));

        JLabel headline = new JLabel("Basket is empty");
        headline.setFont(PosTheme.base(Font.BOLD, 16f));
        headline.setForeground(new Color(0x9A, 0x9E, 0xA3));
        headline.setAlignmentX(CENTER_ALIGNMENT);

        JLabel hint = new JLabel("Scan a barcode or tap a quick-add item to start the sale");
        hint.setFont(PosTheme.base(Font.PLAIN, PosTheme.BODY));
        hint.setForeground(new Color(0xB0, 0xB4, 0xB8));
        hint.setAlignmentX(CENTER_ALIGNMENT);

        stack.add(headline);
        stack.add(Box.createVerticalStrut(6));
        stack.add(hint);
        empty.add(stack);
        return empty;
    }

    /** Left column, bottom cell: the summary tape (Subtotal / Discount / Tax / TOTAL). */
    private JPanel buildSummaryCard() {
        summaryTape = buildSummaryTape();
        renderVerticalSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(PosTheme.SURFACE);
        body.setBorder(BorderFactory.createEmptyBorder(12, 16, 14, 16));
        body.add(summaryTape, BorderLayout.NORTH);
        return PosTheme.card("Summary", body);
    }

    /**
     * Bottom-right, left 60%: the basket actions as a single row of five tall buttons — Void
     * Basket │ Void Line │ Change Qty │ Discount │ Total. One row means no vertical neighbours to
     * mis-tap between, and each button uses the full row height as a single generous target.
     *
     * <p>The order is deliberate. Total is the most-pressed button in the lane and Void Basket
     * destroys the sale, so they must not be adjacent — a fat-finger between the two would be
     * catastrophic. With this ordering Total's only neighbour is Discount (harmless) and the strip
     * reads left-to-right as edit → finalise, flowing into the tender group beside it.</p>
     */
    private JPanel buildActionsCard() {
        changeQtyButton.addActionListener(e -> dispatchWithSelection(PosEventType.CHANGE_QTY_PRESSED));
        voidLineButton.addActionListener(e -> dispatchWithSelection(PosEventType.VOID_LINE_PRESSED));
        // Discount is wired but disabled — enabling it requires the IN_PROGRESS-discount domain
        // change tracked on feature/in-progress-discounts (see PosEventType#DISCOUNT_PRESSED).
        discountButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.DISCOUNT_PRESSED)));
        // Void basket is destructive and one tap away on a touchscreen. Dispatch the "pressed"
        // event and let the controller open the {@link VoidBasketConfirmView} — the view stays
        // dumb, and the confirmation dialog owns the two-step commit through its own event
        // vocabulary (VOID_BASKET_CONFIRM_PRESSED / VOID_BASKET_DECLINED).
        voidBasketButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_PRESSED)));
        totalButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.TOTAL_PRESSED)));
        changeQtyButton.setEnabled(false);
        discountButton.setEnabled(false);

        // Single horizontal row; GridLayout stretches every button to the full section height, so
        // each fills the ~180px row as one tall target. Order: destructive-and-edit on the left,
        // Total on the right, Void Basket kept far from Total.
        JPanel row = new JPanel(new GridLayout(1, 5, PosTheme.BUTTON_GAP, 0));
        row.setOpaque(false);
        row.add(voidBasketButton);
        row.add(voidLineButton);
        row.add(changeQtyButton);
        row.add(discountButton);
        row.add(totalButton);

        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(PosTheme.SURFACE);
        body.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
        body.add(row, BorderLayout.CENTER);

        actionsPanel = PosTheme.card("Actions", body);
        return actionsPanel;
    }

    // ---- Summary tape (vertical stack) ------------------------------------
    //
    // Four rows read top-to-bottom: Subtotal → Discount → Tax → Total. Order matches
    // {@link ReceiptFormatter} exactly — the on-screen summary and the printed receipt agree, so
    // a cashier reading either sees the same arithmetic. Tax is computed on the post-discount
    // subtotal (see {@link com.rocketpartners.onboarding.commons.model.Transaction#taxTotal()}),
    // so listing Tax after Discount matches how the number is derived. The Total row is
    // separated by a hairline rule; component rows share a fixed-width value column so amounts
    // right-align to the same edge as digit-count changes.

    /** Fixed width of the value column, in pixels — anchors right-edge alignment across rows. */
    private static final int SUMMARY_VALUE_COL_WIDTH = 140;
    /** Height of a single component row (Subtotal / Discount / Tax). */
    private static final int SUMMARY_COMPONENT_ROW_HEIGHT = 20;
    /** Height of the Total row — taller so the number reads at register scale. */
    private static final int SUMMARY_TOTAL_ROW_HEIGHT = 38;
    /** Vertical breathing space above and below the Total row. */
    private static final int SUMMARY_TOTAL_BREATH = 4;
    /** Point size of the Total row's value — larger than BUTTON/AMOUNT, less than DISPLAY, to
     *  leave headroom for the amount-due readout in the tender column. */
    private static final float SUMMARY_TOTAL_SIZE = 30f;

    private JPanel buildSummaryTape() {
        JPanel tape = new JPanel();
        tape.setOpaque(false);
        tape.setLayout(new BoxLayout(tape, BoxLayout.Y_AXIS));

        Font componentFont = PosTheme.base(Font.PLAIN, PosTheme.BODY);

        subtotalLabel.setFont(componentFont);
        subtotalLabel.setForeground(PosTheme.MUTED);
        subtotalValue.setFont(componentFont);
        subtotalValue.setForeground(PosTheme.INK);

        discountLabel.setFont(componentFont);
        discountLabel.setForeground(PosTheme.MUTED);
        discountValue.setFont(componentFont);
        // Discount value colour flips between GO (non-zero) and MUTED (zero) in the render pass.

        taxLabel.setFont(componentFont);
        taxLabel.setForeground(PosTheme.MUTED);
        taxValue.setFont(componentFont);
        taxValue.setForeground(PosTheme.INK);

        totalLabel.setFont(PosTheme.eyebrow());
        totalLabel.setForeground(PosTheme.INK);
        totalValue.setFont(PosTheme.base(Font.BOLD, SUMMARY_TOTAL_SIZE));
        totalValue.setForeground(PosTheme.INK);

        tape.add(componentRow(subtotalLabel, subtotalValue));
        tape.add(componentRow(discountLabel, discountValue));
        tape.add(componentRow(taxLabel, taxValue));

        tape.add(Box.createVerticalStrut(SUMMARY_TOTAL_BREATH));
        JPanel hairline = new JPanel();
        hairline.setBackground(PosTheme.RULE);
        hairline.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        hairline.setPreferredSize(new Dimension(10, 1));
        tape.add(hairline);
        tape.add(Box.createVerticalStrut(SUMMARY_TOTAL_BREATH));

        tape.add(totalRow(totalLabel, totalValue));
        return tape;
    }

    private static JPanel componentRow(JLabel label, JLabel value) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, SUMMARY_COMPONENT_ROW_HEIGHT));
        row.add(label, BorderLayout.WEST);
        JPanel valueCell = new JPanel(new BorderLayout());
        valueCell.setOpaque(false);
        valueCell.setPreferredSize(new Dimension(SUMMARY_VALUE_COL_WIDTH, SUMMARY_COMPONENT_ROW_HEIGHT));
        valueCell.add(value, BorderLayout.CENTER);
        row.add(valueCell, BorderLayout.EAST);
        return row;
    }

    private static JPanel totalRow(JLabel label, JLabel value) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, SUMMARY_TOTAL_ROW_HEIGHT));
        row.add(label, BorderLayout.WEST);
        JPanel valueCell = new JPanel(new BorderLayout());
        valueCell.setOpaque(false);
        valueCell.setPreferredSize(new Dimension(SUMMARY_VALUE_COL_WIDTH, SUMMARY_TOTAL_ROW_HEIGHT));
        valueCell.add(value, BorderLayout.CENTER);
        row.add(valueCell, BorderLayout.EAST);
        return row;
    }

    /**
     * Fills the summary tape with the given figures. Discount row is always present — when the
     * discount is zero both label and value render in MUTED (no minus sign) so the eye skips the
     * row without the tape's height changing, which would shift the basket list beneath it.
     * When the discount is non-zero the value renders in GO with a leading minus.
     */
    private void renderVerticalSummary(BigDecimal subtotal, BigDecimal discount, BigDecimal tax,
                                       BigDecimal total) {
        subtotalLabel.setText(lastNonVoidedQuantitySum > 0
                ? "Subtotal  " + itemCountFragment(lastNonVoidedQuantitySum)
                : "Subtotal");
        subtotalValue.setText(PosTheme.money(subtotal));

        boolean hasDiscount = discount.signum() > 0;
        discountLabel.setForeground(hasDiscount ? PosTheme.INK : PosTheme.MUTED);
        discountValue.setForeground(hasDiscount ? PosTheme.GO : PosTheme.MUTED);
        discountValue.setText(hasDiscount
                ? "-" + PosTheme.money(discount)
                : PosTheme.money(discount));

        taxValue.setText(PosTheme.money(tax));
        totalValue.setText(PosTheme.money(total));
    }

    private static String itemCountFragment(int count) {
        return count == 1 ? "1 item" : count + " items";
    }

    private void dispatchWithSelection(PosEventType type) {
        LineItem selected = getSelectedLineItem();
        Map<String, Object> props = new HashMap<>();
        if (selected != null) props.put("lineItem", selected);
        dispatcher.dispatchPosEvent(new PosEvent(type, props));
    }

    /**
     * Bottom-right, right 40%: the tender controls as a single row of three tall buttons — Pay
     * Cash │ Pay Debit │ Pay Credit. One row, no vertical neighbours, each filling the full
     * section height. At this width the colour, not the label, is what tells the three tenders
     * apart — so each keeps its own fill (cash green, debit blue, credit indigo). All three are
     * disabled until Total.
     */
    private JPanel buildPaymentCard() {
        payCashButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED)));
        payDebitButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.TENDER_DEBIT_PRESSED)));
        payCreditButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CREDIT_PRESSED)));
        payCashButton.setEnabled(false);
        payDebitButton.setEnabled(false);
        payCreditButton.setEnabled(false);

        // The three tenders in one horizontal row; GridLayout gives them equal width and stretches
        // each to the full section height. Retained in the cardTenderRow field so layout tests can
        // read the split directly.
        cardTenderRow = new JPanel(new GridLayout(1, 3, PosTheme.BUTTON_GAP, 0));
        cardTenderRow.setOpaque(false);
        cardTenderRow.add(payCashButton);
        cardTenderRow.add(payDebitButton);
        cardTenderRow.add(payCreditButton);

        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(PosTheme.SURFACE);
        body.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
        body.add(cardTenderRow, BorderLayout.CENTER);

        paymentPanel = PosTheme.card("Payment", body);
        return paymentPanel;
    }

    // ---- Density animation -------------------------------------------------

    private void animateDensityTransition(BasketCellRenderer.Density target) {
        if (densityTimer != null) densityTimer.stop();
        densityFromHeight = basketList.getFixedCellHeight();
        densityToHeight = target == BasketCellRenderer.Density.COMPACT
                ? BasketCellRenderer.COMPACT_ROW_HEIGHT
                : BasketCellRenderer.COMFORTABLE_ROW_HEIGHT;
        basketRenderer.setDensity(target);
        densityStartNanos = System.nanoTime();
        densityTimer = new Timer(DENSITY_FRAME_MS, this::onDensityFrame);
        densityTimer.setInitialDelay(0);
        densityTimer.start();
    }

    private void onDensityFrame(ActionEvent e) {
        double elapsedMs = (System.nanoTime() - densityStartNanos) / 1_000_000.0;
        double t = Math.min(1.0, elapsedMs / DENSITY_ANIM_MS);
        // Ease out — starts fast, settles gently. Feels responsive at the top of the animation.
        double eased = 1 - Math.pow(1 - t, 3);
        int height = (int) Math.round(densityFromHeight + (densityToHeight - densityFromHeight) * eased);
        basketList.setFixedCellHeight(height);
        if (t >= 1.0) {
            basketList.setFixedCellHeight(densityToHeight);
            densityTimer.stop();
            densityTimer = null;
        }
    }

    // ---- Flash overlay -----------------------------------------------------

    private void startFlash(int index, boolean isQuantityBump) {
        if (flashTimer != null) flashTimer.stop();
        flashIndex = index;
        flashStartNanos = System.nanoTime();
        basketRenderer.setFlashIndex(index, isQuantityBump);
        basketList.ensureIndexIsVisible(index);
        basketLayer.setFlash(index, 1.0f);
        flashTimer = new Timer(FLASH_FRAME_MS, e -> {
            double elapsedMs = (System.nanoTime() - flashStartNanos) / 1_000_000.0;
            double t = Math.min(1.0, elapsedMs / FLASH_MS);
            float alpha = (float) (1.0 - t);
            basketLayer.setFlash(flashIndex, alpha);
            if (t >= 1.0) {
                flashTimer.stop();
                flashTimer = null;
                flashIndex = -1;
                basketRenderer.setFlashIndex(-1, false);
                basketLayer.setFlash(-1, 0f);
            }
        });
        flashTimer.setInitialDelay(0);
        flashTimer.start();
    }

    // ---- Hover tracking ----------------------------------------------------

    private void installHoverTracking() {
        basketList.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int index = basketList.locationToIndex(e.getPoint());
                if (index >= 0) {
                    Rectangle bounds = basketList.getCellBounds(index, index);
                    if (bounds != null && !bounds.contains(e.getPoint())) {
                        index = -1;
                    }
                }
                setHoverRow(index);
            }
        });
        basketList.addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent e) {
                setHoverRow(-1);
            }
        });
    }

    private void setHoverRow(int index) {
        int previous = basketRenderer.getHoverIndex();
        if (previous == index) return;
        basketRenderer.setHoverIndex(index);
        // Repaint only the affected row bounds — hovering across the list must not repaint the
        // whole thing on every mouse move.
        repaintRow(previous);
        repaintRow(index);
    }

    private void repaintRow(int index) {
        if (index < 0) return;
        Rectangle bounds = basketList.getCellBounds(index, index);
        if (bounds != null) basketList.repaint(bounds);
    }

    // ---- Thin scrollbar ----------------------------------------------------

    private static void styleThinScrollBar(JScrollBar bar) {
        bar.setPreferredSize(new Dimension(6, 0));
        bar.setUnitIncrement(16);
        bar.setUI(new BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() {
                this.thumbColor = new Color(0xC7, 0xC5, 0xBF);
                this.trackColor = PosTheme.SURFACE;
            }
            @Override protected javax.swing.JButton createDecreaseButton(int orientation) {
                return zeroSized();
            }
            @Override protected javax.swing.JButton createIncreaseButton(int orientation) {
                return zeroSized();
            }
            private javax.swing.JButton zeroSized() {
                javax.swing.JButton b = new javax.swing.JButton();
                b.setPreferredSize(new Dimension(0, 0));
                b.setMinimumSize(new Dimension(0, 0));
                b.setMaximumSize(new Dimension(0, 0));
                return b;
            }
            @Override protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(this.thumbColor);
                int pad = 1;
                g2.fillRoundRect(thumbBounds.x + pad, thumbBounds.y + pad,
                        thumbBounds.width - pad * 2, thumbBounds.height - pad * 2, 6, 6);
                g2.dispose();
            }
        });
    }

    // ---- Journal status indicator -----------------------------------------

    /**
     * Compact "● Journal connected / disconnected" indicator that lives in the customer-view
     * header. Painted rather than composed from a bullet character so the dot's colour matches
     * the theme exactly (green when connected, red when down) and stays crisp at any DPI.
     *
     * <p>The label reads {@code "Journal LIVE"} / {@code "Journal OFFLINE"} — clearer than a
     * lone icon and short enough to sit alongside the transaction-phase pill without crowding.</p>
     */
    static final class JournalStatusIndicator extends JLabel {

        /** Diameter of the status dot, in pixels. */
        private static final int DOT_SIZE = 9;
        /** Gap between the dot and the label text. */
        private static final int DOT_GAP = 6;

        /** Header foreground text colour — pale grey, readable on {@link PosTheme#INK}. */
        private static final Color LABEL_FG = new Color(0xC9, 0xD1, 0xD8);

        private boolean connected;

        JournalStatusIndicator() {
            super();
            setOpaque(false);
            setFont(PosTheme.eyebrow());
            setForeground(LABEL_FG);
            // Leave room on the left for the dot painted by paintComponent().
            setBorder(BorderFactory.createEmptyBorder(0, DOT_SIZE + DOT_GAP, 0, 0));
            setConnected(false);
        }

        void setConnected(boolean connected) {
            this.connected = connected;
            setText(connected ? "JOURNAL LIVE" : "JOURNAL OFFLINE");
            setToolTipText(connected
                    ? "Virtual journal socket is connected."
                    : "Virtual journal is unreachable. The sale still completes; entries are logged locally.");
            repaint();
        }

        boolean isConnected() {
            return connected;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(connected ? PosTheme.GO : PosTheme.STOP);
            int y = (getHeight() - DOT_SIZE) / 2;
            g2.fillOval(0, y, DOT_SIZE, DOT_SIZE);
            g2.dispose();
        }
    }

    // ---- Flash overlay panel ----------------------------------------------

    /**
     * Paints a fading green tint over one row of the basket list. Sits above the list in the
     * z-order so it can overlay the selection tint without touching the renderer or the row's
     * background. When there is no active flash it paints nothing and costs nothing.
     */
    private static final class FlashLayerPanel extends JPanel {
        private JList<?> source;
        private int index = -1;
        private float alpha;

        FlashLayerPanel() {
            setOpaque(false);
        }

        void setSource(JList<?> source) {
            this.source = Objects.requireNonNull(source);
        }

        void setFlash(int index, float alpha) {
            this.index = index;
            this.alpha = alpha;
            if (index < 0 || alpha <= 0f) {
                repaint();
                return;
            }
            Rectangle bounds = source.getCellBounds(index, index);
            if (bounds != null) {
                // Translate into layer space via the scrollpane's viewport by walking the
                // component hierarchy. Simpler: repaint the whole layer — the overlay itself
                // is cheap and only paints one row.
                repaint();
            }
        }

        @Override
        protected void paintChildren(Graphics g) {
            super.paintChildren(g);
            if (source == null || index < 0 || alpha <= 0f) return;
            Rectangle cell = source.getCellBounds(index, index);
            if (cell == null) return;

            Rectangle visible = source.getVisibleRect();
            if (!visible.intersects(cell)) return;

            // Map from list coordinates into this layer's coordinates by finding the list's
            // position relative to us.
            java.awt.Point layerOrigin = SwingUtilities.convertPoint(source, 0, 0, this);
            Rectangle painted = new Rectangle(
                    layerOrigin.x + cell.x,
                    layerOrigin.y + cell.y,
                    cell.width,
                    cell.height).intersection(new Rectangle(0, 0, getWidth(), getHeight()));

            if (painted.isEmpty()) return;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int a = Math.max(0, Math.min(255, Math.round(FLASH_PEAK_ALPHA * alpha)));
            g2.setColor(new Color(PosTheme.GO.getRed(), PosTheme.GO.getGreen(),
                    PosTheme.GO.getBlue(), a));
            g2.fillRect(painted.x, painted.y, painted.width, painted.height);
            // Thin left-edge marker in solid GO for a stronger cue on the merged-into row.
            g2.setStroke(new BasicStroke(2f));
            g2.setColor(new Color(PosTheme.GO.getRed(), PosTheme.GO.getGreen(),
                    PosTheme.GO.getBlue(), Math.min(255, a * 4)));
            g2.drawLine(painted.x, painted.y, painted.x, painted.y + painted.height);
            g2.dispose();
        }
    }
}
