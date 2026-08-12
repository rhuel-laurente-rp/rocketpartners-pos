package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The Quick Add card body: a search field and a sort control above a paged grid of tiles over the
 * <em>whole</em> pricebook, with a pagination footer.
 *
 * <p>Search, sort, and page are pure view state kept here — the domain never sees them. The panel
 * holds the full item list once; filtering, sorting, and paging are recomputed in memory when the
 * cashier types, changes the sort, or turns a page. Tile capacity is derived from the grid's laid
 * out size (columns from width, rows from height), so a fixed window yields a fixed tiles-per-page
 * without a magic number.</p>
 *
 * <p><strong>The search field is not a scan input.</strong> It filters the grid only. Scanning is
 * global and independent of focus (see {@code ScannerViewController}), so a barcode read reaches
 * the basket even while the caret sits in this field and never filters the grid.</p>
 */
class QuickAddPanel extends JPanel {

    /** Target minimum tile width; the grid fits as many whole columns as this allows. */
    private static final int TILE_MIN_WIDTH = 165;
    /** Tile face height (the shadow inset is added on top by {@link QuickAddTile}). */
    static final int TILE_HEIGHT = 92;
    private static final int TILE_GAP = PosTheme.BUTTON_GAP;

    /** Sort orderings offered in the header combo. */
    enum SortMode {
        NAME_ASC("Name (A–Z)", Comparator.comparing(QuickAddPanel::label, String.CASE_INSENSITIVE_ORDER)),
        NAME_DESC("Name (Z–A)", NAME_ASC_COMPARATOR().reversed()),
        PRICE_ASC("Price (Low–High)", Comparator.comparing(Item::getUnitPrice)),
        PRICE_DESC("Price (High–Low)", Comparator.<Item, java.math.BigDecimal>comparing(Item::getUnitPrice).reversed());

        private final String label;
        private final Comparator<Item> comparator;

        SortMode(String label, Comparator<Item> comparator) {
            this.label = label;
            this.comparator = comparator;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static Comparator<Item> NAME_ASC_COMPARATOR() {
        return Comparator.comparing(QuickAddPanel::label, String.CASE_INSENSITIVE_ORDER);
    }

    private static String label(Item item) {
        return item.getDisplayLabel();
    }

    private final List<Item> allItems;
    private final Consumer<Item> tileHandler;

    private final JTextField searchField = new JTextField();
    private final JComboBox<SortMode> sortCombo = new JComboBox<>(SortMode.values());
    private final JPanel grid = new JPanel();
    private final JPanel gridHolder = new JPanel(new BorderLayout());

    /**
     * The on-screen QWERTY and its slot in the lower portion of the panel. Hidden by default; the
     * tile grid above ({@link #gridHolder}) reclaims the space when it's hidden. Shown on demand —
     * search is the fallback when a barcode won't scan, so the keyboard doesn't get to hold that
     * space permanently.
     */
    private final JPanel keyboardSlot = new JPanel(new BorderLayout());
    private OnScreenKeyboard keyboard;

    private final PosButton firstButton = pageButton("«");
    private final PosButton prevButton = pageButton("‹");
    private final PosButton nextButton = pageButton("›");
    private final PosButton lastButton = pageButton("»");
    private final PagePill pageBox = new PagePill();
    private final JLabel pagesIndicator = new JLabel("1 of 1 pages", SwingConstants.RIGHT);

    /** Minimum touch target for a pager control, in pixels. A glyph is not a target — each arrow
     *  needs a real hit area around it (the 44px accessibility touch minimum). */
    private static final int PAGER_TOUCH = 44;

    private String query = "";
    private SortMode sort = SortMode.NAME_ASC;
    private int page = 0;
    /** Tiles per page, derived from the grid's laid out size; never below 1. */
    private int capacity = 1;
    private int columns = 1;
    private boolean tilesEnabled = true;

    QuickAddPanel(List<Item> items, Consumer<Item> tileHandler) {
        super(new BorderLayout(0, 10));
        this.allItems = new ArrayList<>(items);
        this.tileHandler = tileHandler;
        setBackground(PosTheme.SURFACE);
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        add(buildHeader(), BorderLayout.NORTH);

        grid.setOpaque(false);
        gridHolder.setOpaque(false);
        gridHolder.add(grid, BorderLayout.NORTH);

        // Centre stack: the tile grid fills the space, the keyboard slot sits at the bottom. When
        // the keyboard is shown the grid shrinks into the space above rather than being covered —
        // the cashier has to see filtered results while typing.
        keyboardSlot.setOpaque(false);
        keyboardSlot.setVisible(false);
        keyboard = new OnScreenKeyboard(searchField, this::hideKeyboard);
        keyboardSlot.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        keyboardSlot.add(keyboard, BorderLayout.CENTER);

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(gridHolder, BorderLayout.CENTER);
        center.add(keyboardSlot, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        add(buildFooter(), BorderLayout.SOUTH);

        wireKeyboardTriggers();

        // Recompute capacity whenever the grid area resizes — the window is fixed, so this fires
        // once on show and then stays put.
        gridHolder.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                recomputeCapacity();
            }
        });

        rebuild();
    }

    // ---- Header: search + sort --------------------------------------------

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);

        searchField.setName("quickAddSearch");
        searchField.setFont(PosTheme.base(Font.PLAIN, PosTheme.BODY));
        searchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PosTheme.RULE, 1),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onSearchChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { onSearchChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { onSearchChanged(); }
        });

        sortCombo.setName("quickAddSort");
        sortCombo.setFont(PosTheme.base(Font.PLAIN, PosTheme.BODY));
        sortCombo.addActionListener(e -> {
            sort = (SortMode) sortCombo.getSelectedItem();
            page = 0;
            rebuild();
        });

        JLabel search = new JLabel("Search");
        search.setFont(PosTheme.eyebrow());
        search.setForeground(PosTheme.MUTED);

        JPanel left = new JPanel(new BorderLayout(8, 0));
        left.setOpaque(false);
        left.add(search, BorderLayout.WEST);
        left.add(searchField, BorderLayout.CENTER);

        header.add(left, BorderLayout.CENTER);
        header.add(sortCombo, BorderLayout.EAST);
        return header;
    }

    private void onSearchChanged() {
        query = searchField.getText() == null ? "" : searchField.getText().trim();
        page = 0;
        rebuild();
        // Clearing the field dismisses the keyboard — one of the documented hide triggers. Guarded
        // on isVisible so a programmatic empty (e.g. on construction) is a cheap no-op.
        if (query.isEmpty() && keyboardSlot.isVisible()) {
            hideKeyboard();
        }
    }

    // ---- On-screen keyboard show/hide -------------------------------------

    private void wireKeyboardTriggers() {
        // Show when the search field takes focus (a tap focuses it); hide when focus truly leaves.
        // Keyboard keys are non-focusable, so tapping one does NOT fire focusLost and the keyboard
        // stays up; tapping a tile, the sort control, or a pager button moves focus and hides it.
        searchField.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { showKeyboard(); }
            @Override public void focusLost(FocusEvent e) { hideKeyboard(); }
        });
        // A tap re-shows the keyboard even when the field already holds focus — after Done (or
        // ESC) dismisses it, focus stays put, so focusGained won't fire again and a tap is the
        // only signal that the cashier wants to keep typing.
        searchField.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { showKeyboard(); }
        });
        // ESC dismisses while the field is focused.
        searchField.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "hideKeyboard");
        searchField.getActionMap().put("hideKeyboard", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { hideKeyboard(); }
        });
    }

    private void showKeyboard() {
        if (keyboardSlot.isVisible()) return;
        keyboardSlot.setVisible(true);
        revalidate();
        repaint();
    }

    /**
     * Hides the on-screen keyboard and lets the tile grid reclaim the space. Public so the view
     * layer can dismiss it when a scan succeeds — a barcode read means the cashier found the item
     * another way, and leaving the keyboard up would be stale UI. Leaves the search text and the
     * grid filter untouched. Idempotent.
     */
    void hideKeyboard() {
        if (!keyboardSlot.isVisible()) return;
        keyboardSlot.setVisible(false);
        revalidate();
        repaint();
    }

    // ---- Footer: pagination -----------------------------------------------

    private JPanel buildFooter() {
        firstButton.addActionListener(e -> goToPage(0));
        prevButton.addActionListener(e -> goToPage(page - 1));
        nextButton.addActionListener(e -> goToPage(page + 1));
        lastButton.addActionListener(e -> goToPage(pageCount() - 1));

        pagesIndicator.setFont(PosTheme.base(Font.PLAIN, PosTheme.ROW));
        pagesIndicator.setForeground(PosTheme.MUTED);

        // Navigation cluster: the four chevrons around the current-page pill, kept tight together.
        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
        controls.add(firstButton);
        controls.add(Box.createHorizontalStrut(6));
        controls.add(prevButton);
        controls.add(Box.createHorizontalStrut(8));
        controls.add(pageBox);
        controls.add(Box.createHorizontalStrut(8));
        controls.add(nextButton);
        controls.add(Box.createHorizontalStrut(6));
        controls.add(lastButton);

        // Justify the row across the full footer width: the chevron cluster is pinned to the far
        // left, the "N of M pages" indicator to the far right.
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(controls, BorderLayout.WEST);
        footer.add(pagesIndicator, BorderLayout.EAST);
        return footer;
    }

    private static PosButton pageButton(String glyph) {
        PosButton b = PosButtons.secondary(glyph);
        b.setTouchMinHeight(PosTheme.BUTTON_HEIGHT_SECONDARY);
        // Secondary buttons default to a BODY-sized glyph, which reads as a hairline chevron on a
        // 44px control. Bump to a bold AMOUNT-sized glyph so the arrow fills the touch target and
        // is legible at a glance.
        b.setFont(PosTheme.base(Font.BOLD, PosTheme.AMOUNT));
        // A drawn chevron is a few pixels wide; give it a real square hit area so the whole
        // control — not just the glyph — is tappable. Fixed size keeps the BoxLayout row from
        // collapsing the button to the glyph's intrinsic width.
        Dimension target = new Dimension(PAGER_TOUCH, PAGER_TOUCH + PosButton.SHADOW_INSET);
        b.setPreferredSize(target);
        b.setMinimumSize(target);
        b.setMaximumSize(target);
        return b;
    }

    // ---- Public API called by CustomerView --------------------------------

    /** Enables/disables every tile (basket-input gate). Pagination and search stay usable. */
    void setTilesEnabled(boolean enabled) {
        this.tilesEnabled = enabled;
        for (Component c : grid.getComponents()) c.setEnabled(enabled);
    }

    // ---- Paging ------------------------------------------------------------

    private void goToPage(int target) {
        int clamped = Math.max(0, Math.min(target, pageCount() - 1));
        if (clamped != page) {
            page = clamped;
            rebuild();
        }
    }

    /** Recompute tiles-per-page from the grid area's current size. */
    private void recomputeCapacity() {
        int w = gridHolder.getWidth();
        int h = gridHolder.getHeight();
        if (w <= 0 || h <= 0) return;
        int cols = Math.max(1, (w + TILE_GAP) / (TILE_MIN_WIDTH + TILE_GAP));
        int rows = Math.max(1, (h + TILE_GAP) / (TILE_HEIGHT + PosButton.SHADOW_INSET + TILE_GAP));
        int newCapacity = cols * rows;
        if (newCapacity != capacity || cols != columns) {
            columns = cols;
            capacity = newCapacity;
            page = Math.min(page, Math.max(0, pageCount() - 1));
            rebuild();
        }
    }

    /** Filter + sort the full list into the currently visible ordering. */
    private List<Item> filteredSorted() {
        String q = query.toLowerCase(Locale.ROOT);
        List<Item> out = new ArrayList<>();
        for (Item item : allItems) {
            if (q.isEmpty() || matches(item, q)) out.add(item);
        }
        out.sort(sort.comparator);
        return out;
    }

    private static boolean matches(Item item, String lowerQuery) {
        String label = item.getDisplayLabel();
        if (label != null && label.toLowerCase(Locale.ROOT).contains(lowerQuery)) return true;
        String upc = item.getUpc();
        return upc != null && upc.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private int pageCount() {
        int total = filteredSorted().size();
        return Math.max(1, (int) Math.ceil(total / (double) capacity));
    }

    /** Items shown on the current page. */
    private List<Item> currentPageItems() {
        List<Item> all = filteredSorted();
        int from = Math.min(page * capacity, all.size());
        int to = Math.min(from + capacity, all.size());
        return all.subList(from, to);
    }

    /** Rebuilds the tile grid and refreshes the pagination footer to the current state. */
    private void rebuild() {
        int pages = pageCount();
        if (page > pages - 1) page = pages - 1;
        if (page < 0) page = 0;

        List<Item> pageItems = currentPageItems();
        int rows = Math.max(1, (int) Math.ceil(pageItems.size() / (double) columns));
        grid.removeAll();
        grid.setLayout(new GridLayout(rows, columns, TILE_GAP, TILE_GAP));
        for (Item item : pageItems) {
            QuickAddTile tile = new QuickAddTile(item.getDisplayLabel().trim(),
                    PosTheme.money(item.getUnitPrice()));
            tile.setEnabled(tilesEnabled);
            tile.addActionListener(e -> {
                // Selecting a tile dismisses the keyboard — the cashier found the item.
                hideKeyboard();
                tileHandler.accept(item);
            });
            grid.add(tile);
        }

        pageBox.setText(String.valueOf(page + 1));
        pagesIndicator.setText((page + 1) + " of " + pages + (pages == 1 ? " page" : " pages"));
        firstButton.setEnabled(page > 0);
        prevButton.setEnabled(page > 0);
        nextButton.setEnabled(page < pages - 1);
        lastButton.setEnabled(page < pages - 1);

        grid.revalidate();
        grid.repaint();
    }

    // ---- Test hooks --------------------------------------------------------

    JTextField getSearchFieldForTest() { return searchField; }
    JComboBox<SortMode> getSortComboForTest() { return sortCombo; }
    OnScreenKeyboard getKeyboardForTest() { return keyboard; }
    boolean isKeyboardVisibleForTest() { return keyboardSlot.isVisible(); }

    /** For the snapshot harness: recompute tiles-per-page from the grid's current laid-out size,
     *  synchronously, without waiting on a queued resize event. */
    void recomputeCapacityForTest() { recomputeCapacity(); }

    /** For the snapshot harness: laid-out height of the keyboard slot (0 when hidden). */
    int keyboardHeightForTest() { return keyboardSlot.isVisible() ? keyboardSlot.getHeight() : 0; }

    /** For tests: drive the search field's focus-gained path without a real native focus. */
    void fireSearchFocusGainedForTest() {
        for (java.awt.event.FocusListener l : searchField.getFocusListeners()) {
            l.focusGained(new FocusEvent(searchField, FocusEvent.FOCUS_GAINED));
        }
    }

    /** For tests: drive the search field's focus-lost path. */
    void fireSearchFocusLostForTest() {
        for (java.awt.event.FocusListener l : searchField.getFocusListeners()) {
            l.focusLost(new FocusEvent(searchField, FocusEvent.FOCUS_LOST));
        }
    }

    /** For tests: simulate a tap (mouse press) on the search field. */
    void fireSearchTapForTest() {
        MouseEvent press = new MouseEvent(searchField, MouseEvent.MOUSE_PRESSED, 0L, 0,
                1, 1, 1, false);
        for (java.awt.event.MouseListener l : searchField.getMouseListeners()) {
            l.mousePressed(press);
        }
    }
    JPanel getGridForTest() { return grid; }
    int getPageForTest() { return page; }
    int getPageCountForTest() { return pageCount(); }
    int getCapacityForTest() { return capacity; }
    int getColumnsForTest() { return columns; }
    List<Item> currentPageItemsForTest() { return currentPageItems(); }
    List<Item> filteredSortedForTest() { return filteredSorted(); }
    int tileCountForTest() { return grid.getComponentCount(); }

    /** For tests: force a deterministic tiles-per-page without a real display, then rebuild. */
    void setCapacityForTest(int columns, int capacity) {
        this.columns = Math.max(1, columns);
        this.capacity = Math.max(1, capacity);
        this.page = Math.min(page, Math.max(0, pageCount() - 1));
        rebuild();
    }

    void setQueryForTest(String q) {
        searchField.setText(q);
    }

    void setSortForTest(SortMode mode) {
        sortCombo.setSelectedItem(mode);
    }

    void firstForTest() { goToPage(0); }
    void prevForTest() { goToPage(page - 1); }
    void nextForTest() { goToPage(page + 1); }
    void lastForTest() { goToPage(pageCount() - 1); }

    // Pager control state — for the "disable, never hide at the boundaries" regression. The
    // buttons are never setVisible(false), so visibility must stay true even when disabled.
    boolean firstEnabledForTest() { return firstButton.isEnabled(); }
    boolean prevEnabledForTest() { return prevButton.isEnabled(); }
    boolean nextEnabledForTest() { return nextButton.isEnabled(); }
    boolean lastEnabledForTest() { return lastButton.isEnabled(); }

    boolean pagerControlsAllVisibleForTest() {
        return firstButton.isVisible() && prevButton.isVisible()
                && nextButton.isVisible() && lastButton.isVisible();
    }

    // ---- Current-page pill -------------------------------------------------

    /**
     * The current-page indicator, drawn as a solid rounded pill in {@link PosTheme#INK} with white
     * text rather than an outlined box. A filled pill reads as "you are here" at a glance, matching
     * the header status pill's treatment; the arrows around it are the navigation, so the pill
     * itself is a marker, not a button. Corner radius equals the height, so it renders as a true
     * pill regardless of digit count.
     */
    private static final class PagePill extends JLabel {
        PagePill() {
            super("1", SwingConstants.CENTER);
            setOpaque(false);
            setFont(PosTheme.base(Font.BOLD, PosTheme.BODY));
            setForeground(Color.WHITE);
            setBorder(BorderFactory.createEmptyBorder(4, 14, 4, 14));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(PosTheme.INK);
            int h = getHeight();
            g2.fillRoundRect(0, 0, getWidth(), h, h, h);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ---- Quick-add tile ----------------------------------------------------

    /**
     * A quick-add tile: description wrapped to at most two lines above the price. Drawn rather
     * than composed from HTML so the price keeps its accent colour and the whole tile dims
     * correctly when basket input is disabled. Inherits {@link PosButton} elevation, so the tile
     * reads as a pressable card, not a static decoration.
     */
    private static final class QuickAddTile extends PosButton {
        private static final int PAD = 10;
        private static final Font DESC_FONT = PosTheme.base(Font.PLAIN, PosTheme.BODY);
        private static final Font PRICE_FONT = PosTheme.base(Font.BOLD, PosTheme.BUTTON);

        private final String description;
        private final String price;

        QuickAddTile(String description, String price) {
            super("", PosTheme.SURFACE, PosTheme.INK, PosTheme.base(Font.PLAIN, PosTheme.BODY));
            this.description = description;
            this.price = price;
            setPreferredSize(new Dimension(10, TILE_HEIGHT + SHADOW_INSET));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            boolean on = isEnabled();
            boolean pressed = on && getModel().isPressed();
            int sink = pressed ? PRESSED_SINK : 0;
            int height = getHeight() - SHADOW_INSET;
            int maxWidth = getWidth() - PAD * 2;

            g2.setFont(DESC_FONT);
            FontMetrics dfm = g2.getFontMetrics();
            List<String> lines = wrap(description, dfm, maxWidth, 2);
            g2.setColor(on ? PosTheme.INK : PosTheme.DISABLED_FG);
            int y = PAD + dfm.getAscent() + sink;
            for (String line : lines) {
                g2.drawString(line, PAD, y);
                y += dfm.getHeight();
            }

            g2.setFont(PRICE_FONT);
            FontMetrics pfm = g2.getFontMetrics();
            g2.setColor(on ? PosTheme.GO : PosTheme.DISABLED_FG);
            g2.drawString(price, PAD, height - PAD - pfm.getDescent() + sink);

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
}
