package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.component.EligibilityRule;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modal eligibility-discount dialog: {@link PosDialog}-shelled, data-driven from the rules the
 * engine returned at startup — never a hard-coded "Senior" / "Veteran" list.
 *
 * <p><strong>Single selection, exclusivity by construction.</strong> The seed eligibility rules
 * all share one exclusivity group, so the dialog offers them as a single radio group: selecting a
 * second discount deselects the first. {@link com.rocketpartners.onboarding.possystem.service.DiscountSession}
 * independently enforces the same one-per-group rule, so the guarantee does not depend on the UI.</p>
 *
 * <p><strong>ID gate.</strong> A cashier must tick "ID Verified" before Confirm becomes available.
 * Real registers gate this, and an unverified senior discount is a well-known shrink route. The
 * flag rides out on {@link PosEventType#DISCOUNT_CONFIRM_PRESSED} and is journalled with the
 * operator id.</p>
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

    private final IPosEventDispatcher dispatcher;

    private final JPanel rulesPanel = new JPanel();
    private final ButtonGroup ruleGroup = new ButtonGroup();
    private final Map<JRadioButton, EligibilityRule> ruleButtons = new LinkedHashMap<>();

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
     * Populates and opens the dialog. Rebuilds the rule rows from {@code rules}, pre-selects the
     * currently-applied rule (if any), resets the ID-Verified gate to unchecked, and clears the
     * message row.
     *
     * @param rules             the eligibility rules to offer; must not be {@code null} (may be empty)
     * @param selectedCodes     codes already applied to the transaction, pre-checked on open; must
     *                          not be {@code null}
     */
    public void openFor(List<EligibilityRule> rules, List<String> selectedCodes) {
        if (rules == null) throw new IllegalArgumentException("rules must not be null");
        if (selectedCodes == null) throw new IllegalArgumentException("selectedCodes must not be null");

        rebuildRules(rules, selectedCodes);
        idVerified.setSelected(false);
        messageLabel.setText(" ");
        refreshConfirmEnabled();
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
        for (Map.Entry<JRadioButton, EligibilityRule> e : ruleButtons.entrySet()) {
            if (e.getKey().isSelected()) return e.getValue();
        }
        return null;
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

        rulesPanel.setOpaque(false);
        rulesPanel.setLayout(new BoxLayout(rulesPanel, BoxLayout.Y_AXIS));
        rulesPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(rulesPanel);

        body.add(Box.createVerticalStrut(12));

        idVerified.setOpaque(false);
        idVerified.setFont(PosTheme.base(Font.BOLD, PosTheme.BODY));
        idVerified.setForeground(PosTheme.INK);
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

    private void rebuildRules(List<EligibilityRule> rules, List<String> selectedCodes) {
        for (JRadioButton b : new ArrayList<>(ruleButtons.keySet())) {
            ruleGroup.remove(b);
        }
        ruleButtons.clear();
        rulesPanel.removeAll();

        if (rules.isEmpty()) {
            JLabel none = new JLabel("No discount rules are available. The discount service may be offline.");
            none.setFont(PosTheme.base(Font.PLAIN, PosTheme.BODY));
            none.setForeground(PosTheme.MUTED);
            none.setAlignmentX(Component.LEFT_ALIGNMENT);
            rulesPanel.add(none);
        } else {
            for (EligibilityRule rule : rules) {
                JRadioButton radio = new JRadioButton(rule.description());
                radio.setOpaque(false);
                radio.setFont(PosTheme.base(Font.PLAIN, PosTheme.ROW));
                radio.setForeground(PosTheme.INK);
                radio.setAlignmentX(Component.LEFT_ALIGNMENT);
                radio.setSelected(selectedCodes.contains(rule.code()));
                radio.addActionListener(e -> {
                    messageLabel.setText(" ");
                    refreshConfirmEnabled();
                });
                ruleGroup.add(radio);
                ruleButtons.put(radio, rule);
                rulesPanel.add(radio);
            }
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

    /** For tests: the rule radio buttons in display order, keyed by rule code. */
    Map<String, JRadioButton> getRuleButtonsForTest() {
        Map<String, JRadioButton> out = new LinkedHashMap<>();
        for (Map.Entry<JRadioButton, EligibilityRule> e : ruleButtons.entrySet()) {
            out.put(e.getValue().code(), e.getKey());
        }
        return out;
    }
}
