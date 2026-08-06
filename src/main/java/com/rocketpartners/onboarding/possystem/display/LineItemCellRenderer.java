package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.LineItem;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import java.awt.Color;
import java.awt.Component;
import java.math.RoundingMode;

/**
 * Renders a {@link LineItem} as {@code qty × description ..... $extendedTotal}. Voided lines
 * are shown in grey with strike-through text; totals are formatted to scale 2, HALF_UP.
 */
final class LineItemCellRenderer extends DefaultListCellRenderer {

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                  boolean isSelected, boolean cellHasFocus) {
        Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        if (value instanceof LineItem li) {
            String qty = String.valueOf(li.getQuantity());
            String desc = li.getItem().getDescription().trim();
            String total = li.extendedTotal().setScale(2, RoundingMode.HALF_UP).toPlainString();
            String label = qty + " × " + desc + "   $" + total;
            if (li.isVoided()) {
                setText("<html><strike>" + escapeHtml(label) + "</strike></html>");
                if (!isSelected) setForeground(Color.GRAY);
            } else {
                setText(label);
            }
        }
        return c;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
