package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.HashMap;
import java.util.Map;

/**
 * Modal change-quantity dialog: a dumb Swing renderer that forwards Confirm and Cancel as
 * {@link PosEvent}s. The controller owns the domain logic.
 *
 * <p>Layout: line description, a {@link JSpinner} with the current quantity, and
 * {@code Confirm} / {@code Cancel} at the bottom. The spinner's numeric model prevents
 * non-integer input entirely — that's the reason the spec calls out a JSpinner rather than a
 * plain text field.</p>
 */
public class ChangeQuantityView {

    private final JDialog dialog;
    private final IPosEventDispatcher dispatcher;

    private final JLabel descriptionLabel = new JLabel(" ");
    private final JSpinner quantitySpinner;
    private final JButton confirmButton = new JButton("Confirm");
    private final JButton cancelButton = new JButton("Cancel");

    private LineItem lineItem;

    /**
     * @param owner       the parent frame; may be {@code null}
     * @param dispatcher  target for view-input events; must not be {@code null}
     * @param maxQuantity upper bound wired to the spinner model (min stays at 0 so the cashier
     *                    can trigger a void via the change-qty path)
     */
    public ChangeQuantityView(JFrame owner, IPosEventDispatcher dispatcher, int maxQuantity) {
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        if (maxQuantity < 1) throw new IllegalArgumentException("maxQuantity must be >= 1");
        this.dispatcher = dispatcher;
        this.dialog = new JDialog(owner, "Change Quantity", true);
        this.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        this.quantitySpinner = new JSpinner(new SpinnerNumberModel(1, 0, maxQuantity, 1));

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        descriptionLabel.setFont(descriptionLabel.getFont().deriveFont(Font.BOLD, 14f));
        content.add(descriptionLabel, BorderLayout.NORTH);

        JPanel middle = new JPanel(new BorderLayout(6, 0));
        middle.add(new JLabel("Quantity:"), BorderLayout.WEST);
        middle.add(quantitySpinner, BorderLayout.CENTER);
        content.add(middle, BorderLayout.CENTER);

        JPanel south = new JPanel(new GridLayout(1, 2, 6, 0));
        south.add(cancelButton);
        south.add(confirmButton);
        content.add(south, BorderLayout.SOUTH);

        confirmButton.addActionListener(e -> {
            Map<String, Object> props = new HashMap<>();
            props.put("lineItem", lineItem);
            props.put("newQuantity", ((Number) quantitySpinner.getValue()).intValue());
            dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CHANGE_QTY_CONFIRM_PRESSED, props));
        });
        cancelButton.addActionListener(e ->
                dispatcher.dispatchPosEvent(new PosEvent(PosEventType.CHANGE_QTY_CANCEL_PRESSED)));

        dialog.getContentPane().add(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
    }

    // ---- Public API called by ChangeQuantityViewController ---------------

    /**
     * Primes the dialog for the given line item and opens it. The dialog remembers the line
     * so Confirm carries it back to the controller — the controller doesn't need to re-look
     * up the target between open and confirm.
     */
    public void openFor(LineItem lineItem) {
        if (lineItem == null) throw new IllegalArgumentException("lineItem must not be null");
        this.lineItem = lineItem;
        descriptionLabel.setText(lineItem.getItem().getDescription());
        quantitySpinner.setValue(lineItem.getQuantity());
        dialog.setVisible(true);
    }

    /** Closes the dialog. */
    public void closeDialog() {
        dialog.setVisible(false);
    }
}
