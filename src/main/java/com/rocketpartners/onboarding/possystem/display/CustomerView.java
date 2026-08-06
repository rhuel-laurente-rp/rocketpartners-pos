package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The customer-facing basket screen: a dumb Swing renderer laid out as a three-column POS shell.
 *
 * <p>Columns, left to right:</p>
 * <ul>
 *   <li><strong>Quick Add.</strong> Grid of buttons; each dispatches a {@link
 *       PosEventType#QUICK_ADD_PRESSED} event carrying its bound UPC.</li>
 *   <li><strong>Basket + basket actions.</strong> Center: scrollable line-item list with a running
 *       total. South: {@code Void Line}, {@code Void Basket}, {@code Total}.</li>
 *   <li><strong>Tender.</strong> {@code Pay Cash}, {@code Pay Debit}, {@code Pay Credit}.
 *       Disabled until Total is pressed. Pressing one dispatches its tender-pressed event so a
 *       child controller can open the appropriate modal dialog (cash entry or card processing).</li>
 * </ul>
 *
 * <p>Per {@code docs/Phase 1/event-flow.md}, a {@code *View} class holds no business logic and
 * has no {@code TransactionService} reference. Its outbound side is one behavior: on any user
 * click, construct a {@link PosEvent} of the appropriate {@link PosEventType} and hand it to
 * the injected {@link IPosEventDispatcher}. Its inbound side is a small public API — {@link
 * #updateBasket(List, BigDecimal)}, {@link #setBasketInputEnabled(boolean)}, {@link
 * #setTenderInputEnabled(boolean)}, {@link #getSelectedLineItem()} — that its controller calls
 * to keep the screen in sync with transaction state.</p>
 *
 * <p>The two {@code setInputEnabled} methods are UI courtesy on top of the real rule; the
 * guarantee that a totaled basket accepts no mutation (and an in-progress one accepts no
 * tender) lives in {@code TransactionService} / {@code Transaction}.</p>
 */
public class CustomerView extends JFrame {

    private static final int PREFERRED_WIDTH = 1100;
    private static final int PREFERRED_HEIGHT = 640;
    private static final int QUICK_ADD_COLS = 2;

    private final IPosEventDispatcher dispatcher;

    private final DefaultListModel<LineItem> basketModel = new DefaultListModel<>();
    private final JList<LineItem> basketList = new JList<>(basketModel);
    private final JLabel runningTotalLabel = new JLabel("Total: $0.00", SwingConstants.RIGHT);

    private final List<JButton> quickAddButtons = new ArrayList<>();
    private final JButton voidLineButton = new JButton("Void Line");
    private final JButton voidBasketButton = new JButton("Void Basket");
    private final JButton totalButton = new JButton("Total");

    private final JButton payCashButton = new JButton("Pay Cash");
    private final JButton payDebitButton = new JButton("Pay Debit");
    private final JButton payCreditButton = new JButton("Pay Credit");

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
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        getContentPane().setLayout(new BorderLayout(0, 0));
        getContentPane().add(buildHeader(title), BorderLayout.NORTH);
        getContentPane().add(buildColumns(quickAddItems), BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null);
    }

    // ---- Public API called by CustomerViewController -----------------------

    /**
     * Replaces the basket contents and running-total display.
     *
     * @param lines        line items to show, in order; may be empty but not {@code null}
     * @param runningTotal current subtotal to display; must not be {@code null}
     */
    public void updateBasket(List<LineItem> lines, BigDecimal runningTotal) {
        if (lines == null) throw new IllegalArgumentException("lines must not be null");
        if (runningTotal == null) throw new IllegalArgumentException("runningTotal must not be null");
        basketModel.clear();
        for (LineItem li : lines) {
            basketModel.addElement(li);
        }
        runningTotalLabel.setText("Total: $" + runningTotal.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    /**
     * Enables or disables the basket-input controls (quick-add buttons, void-line, void-basket,
     * total). Called with {@code false} once {@code Total} is pressed.
     */
    public void setBasketInputEnabled(boolean enabled) {
        for (JButton b : quickAddButtons) b.setEnabled(enabled);
        voidLineButton.setEnabled(enabled);
        voidBasketButton.setEnabled(enabled);
        totalButton.setEnabled(enabled);
    }

    /**
     * Enables or disables the tender controls (Pay Cash / Debit / Credit). Called with
     * {@code true} once {@code Total} is pressed and back to {@code false} after tender or
     * void-basket.
     */
    public void setTenderInputEnabled(boolean enabled) {
        payCashButton.setEnabled(enabled);
        payDebitButton.setEnabled(enabled);
        payCreditButton.setEnabled(enabled);
    }

    /** @return the line item currently selected in the basket list, or {@code null} if none */
    public LineItem getSelectedLineItem() {
        return basketList.getSelectedValue();
    }

    /**
     * Installs the given component as the scan bar at the top of the Basket column.
     * Idempotent — a subsequent call replaces the previous scan bar.
     *
     * @param scanBar the component to mount; must not be {@code null}
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
        header.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));
        header.add(label, BorderLayout.WEST);
        return header;
    }

    private JPanel buildColumns(List<Item> quickAddItems) {
        JPanel columns = new JPanel(new GridLayout(1, 3, 8, 0));
        columns.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));
        columns.add(buildQuickAddColumn(quickAddItems));
        columns.add(buildBasketColumn());
        columns.add(buildTenderColumn());
        return columns;
    }

    private JPanel buildQuickAddColumn(List<Item> quickAddItems) {
        int rows = Math.max(1, (int) Math.ceil(quickAddItems.size() / (double) QUICK_ADD_COLS));
        JPanel grid = new JPanel(new GridLayout(rows, QUICK_ADD_COLS, 6, 6));
        for (Item item : quickAddItems) {
            JButton b = new JButton("<html><center>" + escapeHtml(item.getDescription().trim())
                    + "<br>$" + item.getUnitPrice().setScale(2, RoundingMode.HALF_UP).toPlainString()
                    + "</center></html>");
            b.addActionListener(e -> {
                Map<String, Object> props = new HashMap<>();
                props.put("upc", item.getUpc());
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.QUICK_ADD_PRESSED, props));
            });
            quickAddButtons.add(b);
            grid.add(b);
        }
        JPanel column = new JPanel(new BorderLayout());
        column.setBorder(BorderFactory.createTitledBorder("Quick Add"));
        column.add(new JScrollPane(grid), BorderLayout.CENTER);
        return column;
    }

    private JPanel buildBasketColumn() {
        JPanel column = new JPanel(new BorderLayout(0, 6));
        column.setBorder(BorderFactory.createTitledBorder("Basket"));

        // Slot the ScannerView installs itself into. Kept above the basket list so cause
        // (scan) and effect (line item appearing) sit vertically adjacent.
        column.add(basketNorthSlot, BorderLayout.NORTH);

        basketList.setCellRenderer(new LineItemCellRenderer());
        basketList.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        column.add(new JScrollPane(basketList), BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(6, 6));
        south.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

        runningTotalLabel.setFont(runningTotalLabel.getFont().deriveFont(Font.BOLD, 18f));
        south.add(runningTotalLabel, BorderLayout.NORTH);

        JPanel actions = new JPanel(new GridLayout(1, 3, 6, 0));
        voidLineButton.addActionListener(e -> {
            LineItem selected = getSelectedLineItem();
            Map<String, Object> props = new HashMap<>();
            if (selected != null) props.put("lineItem", selected);
            dispatcher.dispatchPosEvent(new PosEvent(PosEventType.VOID_LINE_PRESSED, props));
        });
        voidBasketButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_PRESSED)));
        totalButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.TOTAL_PRESSED)));
        actions.add(voidLineButton);
        actions.add(voidBasketButton);
        actions.add(totalButton);
        south.add(actions, BorderLayout.CENTER);

        column.add(south, BorderLayout.SOUTH);
        return column;
    }

    private JPanel buildTenderColumn() {
        JPanel column = new JPanel();
        column.setLayout(new BorderLayout(0, 6));
        column.setBorder(BorderFactory.createTitledBorder("Tender"));

        JPanel stack = new JPanel(new GridLayout(3, 1, 0, 8));
        stack.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        for (JButton b : new JButton[]{payCashButton, payDebitButton, payCreditButton}) {
            b.setAlignmentX(Component.CENTER_ALIGNMENT);
            b.setFont(b.getFont().deriveFont(Font.BOLD, 16f));
            b.setEnabled(false);
            stack.add(b);
        }
        payCashButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED)));
        payDebitButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.TENDER_DEBIT_PRESSED)));
        payCreditButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CREDIT_PRESSED)));

        column.add(stack, BorderLayout.NORTH);
        column.add(Box.createGlue(), BorderLayout.CENTER);
        return column;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
