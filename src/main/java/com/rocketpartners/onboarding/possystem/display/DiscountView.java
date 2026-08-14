package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.component.EligibilityRule;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modal eligibility-discount dialog: {@link PosDialog}-shelled, data-driven from the rules the
 * engine returned at startup — never a hard-coded "Senior" / "Veteran" list.
 *
 * <p><strong>Selectable tiles, not radio buttons.</strong> Each eligibility rule is offered as a
 * large selectable tile — the same touch idiom {@link CashModeChoiceView} uses for Exact / Next
 * Dollar. Radio buttons draw a fixed ~14px box that ignores font size and makes a poor fingertip
 * target on a register touchscreen; tiles scale, carry a clear selected state, and reuse the shared
 * button elevation. Selection is single: picking one tile deselects the rest. The seed eligibility
 * rules all share one exclusivity group, so single-selection <em>is</em> one-per-group;
 * {@link com.rocketpartners.onboarding.possystem.service.DiscountSession} independently enforces the
 * same rule so the guarantee never depends on the UI.</p>
 *
 * <p><strong>ID gate.</strong> A cashier must tick "ID Verified" before Confirm becomes available.
 * Real registers gate this, and an unverified senior discount is a well-known shrink route. The
 * gate is a checkbox with a custom 30px glyph inside a 44px hit area — big enough to tap, and
 * styled as a checkbox rather than a button so it is never mistaken for the primary Confirm action.
 * Tapping its label toggles it, not just the box. The flag rides out on
 * {@link PosEventType#DISCOUNT_CONFIRM_PRESSED} and is journalled with the operator id.</p>
 *
 * <p><strong>Engine offline.</strong> If the rules cache is empty (the engine was unreachable at
 * startup) the dialog says so plainly and Confirm stays disabled — sales continue without an
 * eligibility discount rather than the dialog failing.</p>
 *
 * <p>Follows the shared dialog conventions: dark header, secondary (Cancel) on the left and primary
 * (Confirm Discount) on the right, Title Case labels, a reserved inline message row that always
 * holds its slot, and ESC to cancel.</p>
 */
public class DiscountView extends PosDialog {

    /** Body width floor so the rule rows and message lay out without re-packing between opens. */
    private static final int BODY_MIN_WIDTH = 380;

    /**
     * Face height of an eligibility tile. Tall by design — the tiles sit side by side in a row (the
     * same tall-tile idiom as the main window's action buttons and {@link CashModeChoiceView}),
     * not as thin full-width bars, so a big hand gets a generous target. Well above the 56px touch
     * minimum.
     */
    private static final int TILE_FACE_HEIGHT = 92;

    /** Horizontal gap between adjacent eligibility tiles. */
    private static final int TILE_GAP = 10;

    private final IPosEventDispatcher dispatcher;

    private final JPanel rulesPanel = new JPanel();
    /** Tiles in display order, each mapped to the rule it applies. */
    private final Map<SelectableTile, EligibilityRule> ruleTiles = new LinkedHashMap<>();

    private final JCheckBox idVerified = new JCheckBox("ID Verified");
    private final JLabel messageLabel = new JLabel(" ");
    private final PosButton confirmButton;
    private final PosButton cancelButton;

    /**
     * @param owner      the parent frame; may be {@code null}
     * @param dispatcher target for view-input events; must not be {@code null}
     */
    public DiscountView(JFrame owner, IPosEventDispatcher dispatcher) {
        super(owner, "Apply Discount");
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        this.dispatcher = dispatcher;

        this.confirmButton = PosButtons.primary("Confirm Discount");
        this.cancelButton = PosButtons.secondary("Cancel");

        setBody(buildBody());

        confirmButton.addActionListener(e -> onConfirm());
        setPrimary(confirmButton);

        cancelButton.addActionListener(e -> onCancel());
        addSecondary(cancelButton);
        setCancelAction(this::onCancel);

        idVerified.addActionListener(e -> refreshConfirmEnabled());
    }

    // ---- Public API called by DiscountViewController ----------------------

    /**
     * Populates and opens the dialog. Rebuilds the rule tiles from {@code rules}, pre-selects the
     * currently-applied rule (if any), resets the ID-Verified gate to unchecked, and clears the
     * message row.
     *
     * @param rules             the eligibility rules to offer; must not be {@code null} (may be empty)
     * @param selectedCodes     codes already applied to the transaction, pre-selected on open; must
     *                          not be {@code null}
     */
    public void openFor(List<EligibilityRule> rules, List<String> selectedCodes) {
        prepare(rules, selectedCodes);
        openDialog();
    }

    // ---- Handlers ---------------------------------------------------------

    private void onConfirm() {
        EligibilityRule rule = selectedRule();
        if (rule == null) {
            messageLabel.setText("Select a discount to apply.");
            return;
        }
        if (!idVerified.isSelected()) {
            messageLabel.setText("Verify the customer's ID before applying.");
            return;
        }
        closeDialog();
        Map<String, Object> props = new HashMap<>();
        props.put("code", rule.code());
        props.put("description", rule.description());
        if (rule.discountType() != null) props.put("discountType", rule.discountType().name());
        if (rule.amount() != null) props.put("amount", rule.amount());
        if (rule.exclusivityGroup() != null) props.put("exclusivityGroup", rule.exclusivityGroup());
        props.put("idVerified", Boolean.TRUE);
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.DISCOUNT_CONFIRM_PRESSED, props));
    }

    private void onCancel() {
        closeDialog();
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.DISCOUNT_CANCEL_PRESSED));
    }

    private void refreshConfirmEnabled() {
        confirmButton.setEnabled(selectedRule() != null && idVerified.isSelected());
    }

    private EligibilityRule selectedRule() {
        for (Map.Entry<SelectableTile, EligibilityRule> e : ruleTiles.entrySet()) {
            if (e.getKey().isSelectedTile()) return e.getValue();
        }
        return null;
    }

    /** Selects {@code tile}, deselecting every other — single selection across the group. */
    private void select(SelectableTile tile) {
        for (SelectableTile t : ruleTiles.keySet()) {
            t.setSelectedTile(t == tile);
        }
        messageLabel.setText(" ");
        refreshConfirmEnabled();
    }

    // ---- Internals --------------------------------------------------------

    private JPanel buildBody() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        JLabel eyebrow = new JLabel("Eligibility discounts");
        eyebrow.setFont(PosTheme.eyebrow());
        eyebrow.setForeground(PosTheme.MUTED);
        eyebrow.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(eyebrow);
        body.add(Box.createVerticalStrut(8));

        // The tile row's layout and height are set in rebuildRules once the rule count is known.
        rulesPanel.setOpaque(false);
        rulesPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(rulesPanel);

        body.add(Box.createVerticalStrut(12));

        // ID gate: a checkbox with a custom 30px glyph inside a 44px hit area. The label is part of
        // the hit area (JCheckBox default), so tapping the text toggles it. Kept a checkbox, not a
        // button, so it reads as a gate rather than the primary action.
        idVerified.setOpaque(false);
        idVerified.setIcon(new CheckBoxIcon());
        idVerified.setFont(PosTheme.base(Font.BOLD, PosTheme.ROW));
        idVerified.setForeground(PosTheme.INK);
        idVerified.setIconTextGap(12);
        idVerified.setFocusPainted(false);
        // Pad to a 44px minimum hit height around the 30px glyph (7px top/bottom).
        idVerified.setBorder(BorderFactory.createEmptyBorder(7, 2, 7, 2));
        idVerified.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(idVerified);

        body.add(Box.createVerticalStrut(8));

        messageLabel.setFont(PosTheme.base(Font.PLAIN, PosTheme.BODY));
        messageLabel.setForeground(PosTheme.STOP);
        messageLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(messageLabel);

        // Width floor so the dialog doesn't resize as rule labels change between opens.
        JComponent strut = (JComponent) Box.createRigidArea(new Dimension(BODY_MIN_WIDTH, 0));
        strut.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(strut);

        return body;
    }

    /** Rebuilds the tiles and resets the ID gate + message, without opening the modal. */
    private void prepare(List<EligibilityRule> rules, List<String> selectedCodes) {
        if (rules == null) throw new IllegalArgumentException("rules must not be null");
        if (selectedCodes == null) throw new IllegalArgumentException("selectedCodes must not be null");
        rebuildRules(rules, selectedCodes);
        idVerified.setSelected(false);
        messageLabel.setText(" ");
        refreshConfirmEnabled();
    }

    private void rebuildRules(List<EligibilityRule> rules, List<String> selectedCodes) {
        ruleTiles.clear();
        rulesPanel.removeAll();
        int rowHeight = TILE_FACE_HEIGHT + PosButton.SHADOW_INSET;

        if (rules.isEmpty()) {
            rulesPanel.setLayout(new java.awt.BorderLayout());
            JLabel none = new JLabel("No discount rules are available. The discount service may be offline.");
            none.setFont(PosTheme.base(Font.PLAIN, PosTheme.BODY));
            none.setForeground(PosTheme.MUTED);
            rulesPanel.add(none, java.awt.BorderLayout.CENTER);
            rulesPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowHeight));
        } else {
            // One row of equal, tall tiles. GridLayout ignores each tile's own max size and
            // stretches it to the cell, so fixing the row's height makes every tile that tall —
            // the same "constrain the row, not the button" trick CashModeChoiceView uses.
            rulesPanel.setLayout(new GridLayout(1, rules.size(), TILE_GAP, 0));
            for (EligibilityRule rule : rules) {
                SelectableTile tile = new SelectableTile(rule.description());
                tile.setSelectedTile(selectedCodes.contains(rule.code()));
                tile.addActionListener(e -> select(tile));
                ruleTiles.put(tile, rule);
                rulesPanel.add(tile);
            }
            rulesPanel.setPreferredSize(new Dimension(BODY_MIN_WIDTH, rowHeight));
            rulesPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowHeight));
        }
        rulesPanel.revalidate();
        rulesPanel.repaint();
    }

    // ---- Test hooks -------------------------------------------------------

    PosButton getConfirmButtonForTest() {
        return confirmButton;
    }

    PosButton getCancelButtonForTest() {
        return cancelButton;
    }

    JCheckBox getIdVerifiedForTest() {
        return idVerified;
    }

    JLabel getMessageLabelForTest() {
        return messageLabel;
    }

    /** For the snapshot harness: prime the dialog without entering the modal event loop. */
    void prepareForTest(List<EligibilityRule> rules, List<String> selectedCodes) {
        prepare(rules, selectedCodes);
    }

    /** For tests: the offered rule codes, in display order. */
    List<String> ruleCodesForTest() {
        List<String> out = new ArrayList<>();
        for (EligibilityRule rule : ruleTiles.values()) out.add(rule.code());
        return out;
    }

    /** For tests: simulate a tap on the tile for {@code code} (fires its action). */
    void clickRuleForTest(String code) {
        for (Map.Entry<SelectableTile, EligibilityRule> e : ruleTiles.entrySet()) {
            if (e.getValue().code().equals(code)) {
                e.getKey().doClick();
                return;
            }
        }
        throw new IllegalArgumentException("no rule tile for code " + code);
    }

    /** For tests: whether the tile for {@code code} is currently selected. */
    boolean isRuleSelectedForTest(String code) {
        for (Map.Entry<SelectableTile, EligibilityRule> e : ruleTiles.entrySet()) {
            if (e.getValue().code().equals(code)) return e.getKey().isSelectedTile();
        }
        return false;
    }

    // ---- Selectable tile --------------------------------------------------

    /**
     * A large, single-line selectable tile reusing {@link PosButton}'s elevation. When selected it
     * traces a {@link PosTheme#GO} ring inside its rounded face — a clear, scannable selected state
     * without changing the fill. Selection is driven from {@link DiscountView#select}, not from the
     * button model, so the dialog owns mutual exclusivity.
     */
    private static final class SelectableTile extends PosButton {

        /** Precomputed so {@code paintComponent} allocates nothing on the selected-ring path. */
        private static final BasicStroke RING_STROKE = new BasicStroke(2.5f);

        private boolean selected;

        SelectableTile(String text) {
            // HTML so a longer description wraps and stays centred inside the tall, narrower tile
            // rather than being clipped on one line.
            super("<html><center>" + text + "</center></html>",
                    PosTheme.SURFACE, PosTheme.INK, PosTheme.base(Font.PLAIN, PosTheme.ROW));
            setTouchMinHeight(TILE_FACE_HEIGHT);
        }

        void setSelectedTile(boolean selected) {
            if (this.selected == selected) return;
            this.selected = selected;
            repaint();
        }

        boolean isSelectedTile() {
            return selected;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!selected || !isEnabled()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = PosTheme.BUTTON_CORNER_RADIUS;
            int fillH = getHeight() - SHADOW_INSET;
            g2.setStroke(RING_STROKE);
            g2.setColor(PosTheme.GO);
            g2.drawRoundRect(1, 1, getWidth() - 3, fillH - 3, arc, arc);
            g2.dispose();
        }
    }

    // ---- ID-Verified checkbox glyph ---------------------------------------

    /**
     * A 30px checkbox glyph: an empty rounded square with a {@link PosTheme#RULE} outline when
     * unchecked, a filled {@link PosTheme#GO} square with a white check when checked. Sized far
     * above Swing's fixed ~14px default so it reads as a real touch target. Reads the checked state
     * off the {@link AbstractButton} it is painted for, so it needs no back-reference.
     */
    private static final class CheckBoxIcon implements Icon {

        private static final int SIZE = 30;
        private static final int ARC = 8;
        private static final BasicStroke CHECK_STROKE =
                new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        private static final BasicStroke BOX_STROKE = new BasicStroke(2f);

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            boolean selected = c instanceof AbstractButton b && b.isSelected();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selected) {
                g2.setColor(PosTheme.GO);
                g2.fillRoundRect(x, y, SIZE, SIZE, ARC, ARC);
                g2.setColor(Color.WHITE);
                g2.setStroke(CHECK_STROKE);
                g2.drawLine(x + 7, y + 16, x + 13, y + 22);
                g2.drawLine(x + 13, y + 22, x + 23, y + 9);
            } else {
                g2.setColor(PosTheme.SURFACE);
                g2.fillRoundRect(x, y, SIZE, SIZE, ARC, ARC);
                g2.setColor(PosTheme.RULE);
                g2.setStroke(BOX_STROKE);
                g2.drawRoundRect(x, y, SIZE - 1, SIZE - 1, ARC, ARC);
            }
            g2.dispose();
        }
    }
}
