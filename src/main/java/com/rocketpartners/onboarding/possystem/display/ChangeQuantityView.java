package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.HashMap;
import java.util.Map;

/**
 * Modal change-quantity dialog: {@link PosDialog}-shelled, compact.
 *
 * <p>Body: the line description at {@link PosTheme#ROW} weight, and a {@link JSpinner} sized to
 * {@link PosTheme#HEADLINE} — big enough that the number is legible and the tap targets on the
 * up/down arrows are reachable without aim.</p>
 *
 * <p>The spinner's numeric model prevents non-integer input entirely — that's why the spec
 * specified a spinner rather than a text field. Its range is {@code [0, maxQuantity]}: zero is
 * allowed so the cashier can trigger a void via the change-qty path, and the max mirrors the
 * service's {@code maxLineQuantity} guard.</p>
 *
 * <p>Confirm relabels to <strong>Void line</strong> in {@link PosTheme#STOP} colours when the
 * spinner sits at zero. The cashier sees the consequence <em>before</em> committing, not after
 * — a change to zero and pressing Void line produce byte-identical state, so the button label
 * should match the outcome.</p>
 */
public class ChangeQuantityView extends PosDialog {

    private final IPosEventDispatcher dispatcher;

    private final JLabel descriptionLabel = new JLabel(" ");
    private final JSpinner quantitySpinner;
    private final PosButton confirmButton;
    private final PosButton confirmZeroButton;

    /**
     * True while the current primary is the {@code confirmZeroButton}. Tracked separately from
     * button identity because {@link PosDialog#setPrimary(PosButton)} replaces the footer slot
     * atomically; this flag lets {@link #confirmButtonForSpinner()} avoid churn when the
     * spinner value moves within the same "zone".
     */
    private boolean primaryIsZeroVariant;

    private LineItem lineItem;

    /**
     * @param owner       the parent frame; may be {@code null}
     * @param dispatcher  target for view-input events; must not be {@code null}
     * @param maxQuantity upper bound wired to the spinner model
     */
    public ChangeQuantityView(JFrame owner, IPosEventDispatcher dispatcher, int maxQuantity) {
        super(owner, "Change quantity");
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        if (maxQuantity < 1) throw new IllegalArgumentException("maxQuantity must be >= 1");
        this.dispatcher = dispatcher;

        this.quantitySpinner = new JSpinner(new SpinnerNumberModel(1, 0, maxQuantity, 1));
        this.confirmButton = PosButtons.primary("Confirm change");
        this.confirmZeroButton = PosButtons.danger("Void line");

        setBody(buildBody());

        confirmButton.addActionListener(e -> fireConfirm());
        confirmZeroButton.addActionListener(e -> fireConfirm());
        setPrimary(confirmButton);

        PosButton cancel = PosButtons.secondary("Cancel");
        cancel.addActionListener(e -> fireCancel());
        addSecondary(cancel);
        setCancelAction(this::fireCancel);
        setInitialFocus(quantitySpinner);

        quantitySpinner.addChangeListener(e -> updatePrimaryForValue());
    }

    // ---- Public API called by ChangeQuantityViewController ----------------

    public void openFor(LineItem lineItem) {
        if (lineItem == null) throw new IllegalArgumentException("lineItem must not be null");
        this.lineItem = lineItem;
        descriptionLabel.setText(lineItem.getItem().getDescription());
        quantitySpinner.setValue(lineItem.getQuantity());
        updatePrimaryForValue();
        openDialog();
    }

    // ---- Handlers ---------------------------------------------------------

    private void fireConfirm() {
        Map<String, Object> props = new HashMap<>();
        props.put("lineItem", lineItem);
        props.put("newQuantity", ((Number) quantitySpinner.getValue()).intValue());
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CHANGE_QTY_CONFIRM_PRESSED, props));
    }

    private void fireCancel() {
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CHANGE_QTY_CANCEL_PRESSED));
    }

    private void updatePrimaryForValue() {
        int value = ((Number) quantitySpinner.getValue()).intValue();
        boolean zero = value == 0;
        if (zero != primaryIsZeroVariant) {
            setPrimary(zero ? confirmZeroButton : confirmButton);
            primaryIsZeroVariant = zero;
        }
    }

    // ---- Layout -----------------------------------------------------------

    private JPanel buildBody() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        descriptionLabel.setFont(PosTheme.base(Font.BOLD, PosTheme.ROW));
        descriptionLabel.setForeground(PosTheme.INK);
        descriptionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(descriptionLabel);
        body.add(Box.createVerticalStrut(14));

        JLabel eyebrow = new JLabel("QUANTITY");
        eyebrow.setFont(PosTheme.eyebrow());
        eyebrow.setForeground(PosTheme.MUTED);
        eyebrow.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(eyebrow);
        body.add(Box.createVerticalStrut(6));

        quantitySpinner.setFont(PosTheme.base(Font.BOLD, PosTheme.HEADLINE));
        JComponent editor = quantitySpinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor de) {
            de.getTextField().setFont(PosTheme.base(Font.BOLD, PosTheme.HEADLINE));
            de.getTextField().setColumns(4);
        }
        quantitySpinner.setPreferredSize(new Dimension(140, 56));
        quantitySpinner.setMaximumSize(new Dimension(180, 56));
        quantitySpinner.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(quantitySpinner);
        return body;
    }
}
