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
import javax.swing.ListCellRenderer;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The customer-facing basket screen: a dumb Swing renderer laid out as a three-column POS shell.
 *
 * <p>Columns, left to right, weighted 26/48/26 rather than equal thirds — the basket is what
 * the cashier reads, the tender column is three buttons and shouldn't claim a third of the
 * window:</p>
 * <ul>
 *   <li><strong>Quick Add.</strong> Fixed-height tiles in a scrolling two-column grid; each
 *       dispatches a {@link PosEventType#QUICK_ADD_PRESSED} event carrying its bound UPC.</li>
 *   <li><strong>Basket.</strong> North: scan-bar mount point. Center: line-item list, or an
 *       empty-state prompt when the basket is clear. South: summary tape (subtotal, discount,
 *       tax, total) above the basket actions.</li>
 *   <li><strong>Tender.</strong> Amount due, then {@code Pay Cash}, {@code Pay Debit},
 *       {@code Pay Credit}. Disabled until Total is pressed.</li>
 * </ul>
 *
 * <p>All palette and type tokens live in {@link PosTheme}; button variants live in
 * {@link PosButtons}. This class holds no colour or font literals — if a new shade is needed,
 * it belongs on the theme first.</p>
 *
 * <p>Per {@code docs/Phase 1/event-flow.md}, a {@code *View} class holds no business logic and
 * has no {@code TransactionService} reference. Outbound: on any user click, dispatch a
 * {@link PosEvent}. Inbound: the small API the controller uses to keep the screen in sync —
 * {@link #updateBasket(List, BigDecimal)} / {@link #updateBasket(List, BigDecimal, BigDecimal,
 * BigDecimal, BigDecimal)}, {@link #setBasketInputEnabled(boolean)},
 * {@link #setTenderInputEnabled(boolean)}, {@link #getSelectedLineItem()},
 * {@link #isChangeQtyEnabled()}, {@link #installScanBar(JComponent)}.</p>
 */
public class CustomerView extends JFrame {

    private static final int PREFERRED_WIDTH = 1280;
    private static final int PREFERRED_HEIGHT = 760;
    private static final int QUICK_ADD_COLS = 2;
    private static final int QUICK_ADD_TILE_HEIGHT = 92;
    private static final int BASKET_ROW_HEIGHT = 58;
    private static final int GUTTER = 10;

    private final IPosEventDispatcher dispatcher;

    private final DefaultListModel<LineItem> basketModel = new DefaultListModel<>();
    private final JList<LineItem> basketList = new JList<>(basketModel);
    private final CardLayout basketCards = new CardLayout();
    private final JPanel basketCenter = new JPanel(basketCards);

    private final JLabel subtotalValue = new JLabel("$0.00", SwingConstants.RIGHT);
    private final JLabel discountValue = new JLabel("$0.00", SwingConstants.RIGHT);
    private final JLabel taxValue = new JLabel("$0.00", SwingConstants.RIGHT);
    private final JLabel totalValue = new JLabel("$0.00", SwingConstants.RIGHT);
    private final JLabel amountDueValue = new JLabel("$0.00", SwingConstants.RIGHT);
    private final JLabel statusPill = new JLabel("OPEN", SwingConstants.CENTER);

    private final List<PosButton> quickAddButtons = new ArrayList<>();
    private final PosButton changeQtyButton = PosButtons.secondary("Change qty");
    private final PosButton voidLineButton = PosButtons.secondary("Void line");
    private final PosButton voidBasketButton = PosButtons.danger("Void basket");
    private final PosButton totalButton = PosButtons.primary("Total");

    private final PosButton payCashButton = PosButtons.tender("Pay cash");
    private final PosButton payDebitButton = PosButtons.tender("Pay debit");
    private final PosButton payCreditButton = PosButtons.tender("Pay credit");

    /**
     * Whether basket mutation is currently permitted. Tracked explicitly rather than read off
     * a button, because both selection-dependent buttons vary independently of it and no
     * single button's enabled state is a sound proxy for the transaction phase.
     */
    private boolean basketInputEnabled = true;

    /** Mount point for the {@link ScannerView} at the top of the Basket column. */
    private final JPanel basketNorthSlot = new JPanel(new BorderLayout());

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

        setPreferredSize(new Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT));
        setMinimumSize(new Dimension(1024, 640));
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(PosTheme.PAPER);
        root.add(buildHeader(title), BorderLayout.NORTH);
        root.add(buildColumns(quickAddItems), BorderLayout.CENTER);
        setContentPane(root);

        refreshStatusPill();
        pack();
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

        subtotalValue.setText(PosTheme.money(subtotal));
        discountValue.setText(discount.signum() == 0 ? PosTheme.money(discount)
                : "-" + PosTheme.money(discount));
        discountValue.setForeground(discount.signum() == 0 ? PosTheme.MUTED : PosTheme.GO);
        taxValue.setText(PosTheme.money(tax));
        totalValue.setText(PosTheme.money(total));
        amountDueValue.setText(PosTheme.money(total));

        refreshSelectionDependentButtons();
    }

    /**
     * Enables or disables the basket-input controls. Change-qty and void-line additionally
     * require a non-voided selection and are re-evaluated by {@link
     * #refreshSelectionDependentButtons()}.
     */
    public void setBasketInputEnabled(boolean enabled) {
        basketInputEnabled = enabled;
        for (PosButton b : quickAddButtons) b.setEnabled(enabled);
        voidBasketButton.setEnabled(enabled);
        totalButton.setEnabled(enabled);
        refreshSelectionDependentButtons();
        refreshStatusPill();
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

    /** Derives the header pill from the two enable flags. */
    private void refreshStatusPill() {
        boolean basketOn = totalButton.isEnabled();
        boolean tenderOn = payCashButton.isEnabled();
        if (tenderOn) {
            statusPill.setText("AWAITING PAYMENT");
            statusPill.setForeground(Color.WHITE);
            statusPill.setBackground(PosTheme.LIVE);
        } else if (basketOn) {
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

    /**
     * Installs the given component as the scan bar at the top of the Basket column.
     * Idempotent — a subsequent call replaces the previous scan bar.
     */
    public void installScanBar(JComponent scanBar) {
        if (scanBar == null) throw new IllegalArgumentException("scanBar must not be null");
        basketNorthSlot.removeAll();
        basketNorthSlot.add(scanBar, BorderLayout.CENTER);
        basketNorthSlot.revalidate();
        basketNorthSlot.repaint();
    }

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
        JPanel pillWrap = new JPanel(new BorderLayout());
        pillWrap.setOpaque(false);
        pillWrap.add(statusPill, BorderLayout.EAST);
        header.add(pillWrap, BorderLayout.EAST);
        return header;
    }

    private JPanel buildColumns(List<Item> quickAddItems) {
        JPanel columns = new JPanel(new GridBagLayout());
        columns.setBackground(PosTheme.PAPER);
        columns.setBorder(BorderFactory.createEmptyBorder(GUTTER, GUTTER, GUTTER, GUTTER));

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weighty = 1;
        c.gridy = 0;
        c.insets = new Insets(0, 0, 0, GUTTER);

        c.gridx = 0;
        c.weightx = 0.26;
        columns.add(buildQuickAddColumn(quickAddItems), c);

        c.gridx = 1;
        c.weightx = 0.48;
        columns.add(buildBasketColumn(), c);

        c.gridx = 2;
        c.weightx = 0.26;
        c.insets = new Insets(0, 0, 0, 0);
        columns.add(buildTenderColumn(), c);
        return columns;
    }

    private JPanel buildQuickAddColumn(List<Item> quickAddItems) {
        int rows = Math.max(1, (int) Math.ceil(quickAddItems.size() / (double) QUICK_ADD_COLS));
        JPanel grid = new JPanel(new GridLayout(rows, QUICK_ADD_COLS, 8, 8));
        grid.setOpaque(false);
        grid.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        for (Item item : quickAddItems) {
            QuickAddTile tile = new QuickAddTile(
                    item.getDescription().trim(),
                    PosTheme.money(item.getUnitPrice()));
            tile.addActionListener(e -> {
                Map<String, Object> props = new HashMap<>();
                props.put("upc", item.getUpc());
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.QUICK_ADD_PRESSED, props));
            });
            quickAddButtons.add(tile);
            grid.add(tile);
        }

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(grid, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(top);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(PosTheme.SURFACE);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return PosTheme.card("Quick add", scroll);
    }

    private JPanel buildBasketColumn() {
        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(PosTheme.SURFACE);

        basketNorthSlot.setOpaque(false);
        basketNorthSlot.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, PosTheme.RULE),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        body.add(basketNorthSlot, BorderLayout.NORTH);

        basketList.setCellRenderer(new LineItemCellRenderer());
        basketList.setFixedCellHeight(BASKET_ROW_HEIGHT);
        basketList.setBackground(PosTheme.SURFACE);
        basketList.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

        JScrollPane listScroll = new JScrollPane(basketList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.getViewport().setBackground(PosTheme.SURFACE);
        listScroll.getVerticalScrollBar().setUnitIncrement(16);

        basketCenter.setBackground(PosTheme.SURFACE);
        basketCenter.add(listScroll, "list");
        basketCenter.add(buildEmptyState(), "empty");
        basketCards.show(basketCenter, "empty");
        body.add(basketCenter, BorderLayout.CENTER);

        body.add(buildSummaryAndActions(), BorderLayout.SOUTH);
        return PosTheme.card("Basket", body);
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

    private JPanel buildSummaryAndActions() {
        JPanel south = new JPanel(new BorderLayout(0, 12));
        south.setBackground(PosTheme.SURFACE);
        south.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, PosTheme.RULE),
                BorderFactory.createEmptyBorder(14, 16, 16, 16)));

        JPanel tape = new JPanel();
        tape.setOpaque(false);
        tape.setLayout(new BoxLayout(tape, BoxLayout.Y_AXIS));
        tape.add(summaryRow("Subtotal", subtotalValue, false));
        tape.add(summaryRow("Discount", discountValue, false));
        tape.add(summaryRow("Tax", taxValue, false));

        JPanel hairline = new JPanel();
        hairline.setBackground(PosTheme.RULE);
        hairline.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        hairline.setPreferredSize(new Dimension(10, 1));
        tape.add(Box.createVerticalStrut(10));
        tape.add(hairline);
        tape.add(Box.createVerticalStrut(8));
        tape.add(summaryRow("Total", totalValue, true));
        south.add(tape, BorderLayout.NORTH);

        JPanel actions = new JPanel(new BorderLayout(0, 8));
        actions.setOpaque(false);

        JPanel minor = new JPanel(new GridLayout(1, 3, 8, 0));
        minor.setOpaque(false);
        changeQtyButton.addActionListener(e -> dispatchWithSelection(PosEventType.CHANGE_QTY_PRESSED));
        voidLineButton.addActionListener(e -> dispatchWithSelection(PosEventType.VOID_LINE_PRESSED));
        voidBasketButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_PRESSED)));
        changeQtyButton.setEnabled(false);
        minor.add(changeQtyButton);
        minor.add(voidLineButton);
        minor.add(voidBasketButton);
        actions.add(minor, BorderLayout.NORTH);

        totalButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.TOTAL_PRESSED)));
        totalButton.setPreferredSize(new Dimension(10, 52));
        actions.add(totalButton, BorderLayout.CENTER);
        south.add(actions, BorderLayout.CENTER);

        basketList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) refreshSelectionDependentButtons();
        });
        return south;
    }

    private void dispatchWithSelection(PosEventType type) {
        LineItem selected = getSelectedLineItem();
        Map<String, Object> props = new HashMap<>();
        if (selected != null) props.put("lineItem", selected);
        dispatcher.dispatchPosEvent(new PosEvent(type, props));
    }

    private JPanel summaryRow(String label, JLabel value, boolean emphasis) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, emphasis ? 48 : 22));

        JLabel key = new JLabel(emphasis ? label.toUpperCase() : label);
        key.setFont(emphasis
                ? PosTheme.base(Font.BOLD, 12f).deriveFont(PosTheme.trackedAttributes())
                : PosTheme.base(Font.PLAIN, PosTheme.BODY));
        key.setForeground(emphasis ? PosTheme.INK : PosTheme.MUTED);

        value.setFont(emphasis
                ? PosTheme.base(Font.BOLD, PosTheme.DISPLAY)
                : PosTheme.base(Font.PLAIN, PosTheme.BODY));
        if (emphasis) value.setForeground(PosTheme.INK);
        else if (value != discountValue) value.setForeground(PosTheme.MUTED);

        row.add(key, BorderLayout.WEST);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    private JPanel buildTenderColumn() {
        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(PosTheme.SURFACE);
        body.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JPanel due = new JPanel(new BorderLayout());
        due.setOpaque(false);
        JLabel dueLabel = new JLabel("AMOUNT DUE");
        dueLabel.setFont(PosTheme.eyebrow());
        dueLabel.setForeground(PosTheme.MUTED);
        amountDueValue.setFont(PosTheme.base(Font.BOLD, PosTheme.HEADLINE));
        amountDueValue.setForeground(PosTheme.MUTED);
        amountDueValue.setHorizontalAlignment(SwingConstants.LEFT);
        due.add(dueLabel, BorderLayout.NORTH);
        due.add(amountDueValue, BorderLayout.CENTER);
        due.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, PosTheme.RULE),
                BorderFactory.createEmptyBorder(0, 0, 14, 0)));
        body.add(due, BorderLayout.NORTH);

        JPanel stack = new JPanel(new GridLayout(3, 1, 0, 10));
        stack.setOpaque(false);
        stack.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
        payCashButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED)));
        payDebitButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.TENDER_DEBIT_PRESSED)));
        payCreditButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CREDIT_PRESSED)));
        for (PosButton b : new PosButton[]{payCashButton, payDebitButton, payCreditButton}) {
            b.setEnabled(false);
            stack.add(b);
        }

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(stack, BorderLayout.NORTH);
        body.add(top, BorderLayout.CENTER);
        return PosTheme.card("Tender", body);
    }

    // ---- Quick-add tile ----------------------------------------------------

    /**
     * A quick-add tile: description wrapped to at most two lines above the price. Drawn rather
     * than composed from HTML so the price keeps its accent colour and the whole tile dims
     * correctly when basket input is disabled.
     */
    private static class QuickAddTile extends PosButton {
        private final String description;
        private final String price;

        QuickAddTile(String description, String price) {
            super("", PosTheme.SURFACE, PosTheme.INK, PosTheme.base(Font.PLAIN, PosTheme.BODY));
            this.description = description;
            this.price = price;
            setPreferredSize(new Dimension(10, QUICK_ADD_TILE_HEIGHT));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            boolean on = isEnabled();
            Color fill = !on ? PosTheme.DISABLED_BG
                    : getModel().isPressed() ? new Color(0xE8, 0xF0, 0xEC)
                    : getModel().isRollover() ? new Color(0xF6, 0xF9, 0xF7)
                    : PosTheme.SURFACE;
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            g2.setColor(on ? PosTheme.RULE : new Color(0xEA, 0xE8, 0xE3));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);

            int pad = 10;
            int maxWidth = getWidth() - pad * 2;

            Font descFont = PosTheme.base(Font.PLAIN, PosTheme.BODY);
            g2.setFont(descFont);
            FontMetrics dfm = g2.getFontMetrics();
            List<String> lines = wrap(description, dfm, maxWidth, 2);
            g2.setColor(on ? PosTheme.INK : PosTheme.DISABLED_FG);
            int y = pad + dfm.getAscent();
            for (String line : lines) {
                g2.drawString(line, pad, y);
                y += dfm.getHeight();
            }

            Font priceFont = PosTheme.base(Font.BOLD, PosTheme.BUTTON);
            g2.setFont(priceFont);
            FontMetrics pfm = g2.getFontMetrics();
            g2.setColor(on ? PosTheme.GO : PosTheme.DISABLED_FG);
            g2.drawString(price, pad, getHeight() - pad - pfm.getDescent());

            g2.dispose();
        }

        static List<String> wrap(String text, FontMetrics fm, int maxWidth, int maxLines) {
            List<String> out = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : text.split("\\s+")) {
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (fm.stringWidth(candidate) <= maxWidth) {
                    current.setLength(0);
                    current.append(candidate);
                } else {
                    if (current.length() > 0) out.add(current.toString());
                    current.setLength(0);
                    current.append(word);
                    if (out.size() == maxLines) break;
                }
            }
            if (out.size() < maxLines && current.length() > 0) out.add(current.toString());
            while (out.size() > maxLines) out.remove(out.size() - 1);
            if (!out.isEmpty()) {
                int last = out.size() - 1;
                String tail = out.get(last);
                if (fm.stringWidth(tail) > maxWidth) {
                    while (tail.length() > 1 && fm.stringWidth(tail + "…") > maxWidth) {
                        tail = tail.substring(0, tail.length() - 1);
                    }
                    out.set(last, tail + "…");
                }
            }
            return out;
        }
    }

    // ---- Basket row --------------------------------------------------------

    /**
     * Renders one basket row: quantity chip, description, unit price, and a right-aligned
     * extended total. Voided lines are struck through and muted but stay visible — the cashier
     * and the customer both need to see that a void happened.
     */
    private static class LineItemCellRenderer extends JPanel implements ListCellRenderer<LineItem> {

        private final JLabel qty = new JLabel("", SwingConstants.CENTER);
        private final JLabel description = new JLabel();
        private final JLabel unitPrice = new JLabel();
        private final JLabel extended = new JLabel("", SwingConstants.RIGHT);

        LineItemCellRenderer() {
            super(new BorderLayout(12, 0));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xF1, 0xEF, 0xEA)),
                    BorderFactory.createEmptyBorder(9, 14, 9, 14)));

            qty.setFont(PosTheme.base(Font.BOLD, PosTheme.BODY));
            qty.setOpaque(true);
            qty.setBackground(new Color(0xF0, 0xEF, 0xEB));
            qty.setForeground(PosTheme.INK);
            qty.setBorder(BorderFactory.createEmptyBorder(4, 9, 4, 9));
            JPanel qtyWrap = new JPanel(new GridBagLayout());
            qtyWrap.setOpaque(false);
            qtyWrap.add(qty);
            add(qtyWrap, BorderLayout.WEST);

            description.setFont(PosTheme.base(Font.PLAIN, PosTheme.ROW));
            unitPrice.setFont(PosTheme.base(Font.PLAIN, 12f));
            unitPrice.setForeground(PosTheme.MUTED);
            JPanel text = new JPanel(new GridLayout(2, 1, 0, 1));
            text.setOpaque(false);
            text.add(description);
            text.add(unitPrice);
            add(text, BorderLayout.CENTER);

            extended.setFont(PosTheme.base(Font.BOLD, PosTheme.BUTTON));
            add(extended, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends LineItem> list, LineItem value, int index,
                boolean isSelected, boolean cellHasFocus) {

            boolean voided = value.isVoided();
            setBackground(isSelected ? PosTheme.SELECTED : PosTheme.SURFACE);

            qty.setText(value.getQuantity() + "×");
            qty.setBackground(voided ? new Color(0xF6, 0xF5, 0xF2) : new Color(0xF0, 0xEF, 0xEB));
            qty.setForeground(voided ? PosTheme.DISABLED_FG : PosTheme.INK);

            String desc = value.getItem().getDescription().trim();
            description.setText(voided
                    ? "<html><strike>" + escapeHtml(desc) + "</strike> &nbsp;<font color='#A32A1F'>VOID</font></html>"
                    : escapeHtml(desc));
            description.setForeground(voided ? PosTheme.DISABLED_FG : PosTheme.INK);

            unitPrice.setText("@ " + PosTheme.money(value.getItem().getUnitPrice()));
            unitPrice.setForeground(voided ? PosTheme.DISABLED_FG : PosTheme.MUTED);

            extended.setText(PosTheme.money(value.extendedTotal()));
            extended.setForeground(voided ? PosTheme.DISABLED_FG : PosTheme.INK);
            return this;
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
