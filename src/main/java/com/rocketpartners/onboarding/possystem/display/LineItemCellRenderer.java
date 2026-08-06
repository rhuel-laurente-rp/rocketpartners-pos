package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.LineItem;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import java.awt.Color;
import java.awt.Component;
import java.math.RoundingMode;

/**
 * Renders a {@link LineItem} as {@code qty  description  @ unitPrice   extendedTotal}, with
 * the extended total right-aligned. Voided lines are shown in grey with strike-through text;
 * money is formatted to scale 2, HALF_UP.
 *
 * <p>Quantity is visible on every row — a change-quantity feature is pointless if the cashier
 * can't see the current count. A table layout via HTML lets Swing's default cell renderer
 * right-align the extended total without additional column-based cell painting.</p>
 */
final class LineItemCellRenderer extends DefaultListCellRenderer {

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                  boolean isSelected, boolean cellHasFocus) {
        Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        if (value instanceof LineItem li) {
            String qty = String.valueOf(li.getQuantity());
            String desc = escapeHtml(li.getItem().getDescription().trim());
            String unitPrice = li.getItem().getUnitPrice().setScale(2, RoundingMode.HALF_UP).toPlainString();
            String total = li.extendedTotal().setScale(2, RoundingMode.HALF_UP).toPlainString();

            int listWidth = list.getWidth() > 0 ? list.getWidth() : 400;
            String leftCell = qty + " &nbsp;&nbsp;" + desc
                    + " &nbsp;&nbsp;@ $" + unitPrice;
            String rightCell = "$" + total;
            String row = "<html><table width='" + Math.max(200, listWidth - 24) + "' cellpadding='0'>"
                    + "<tr><td align='left'>" + wrap(leftCell, li.isVoided()) + "</td>"
                    + "<td align='right'>" + wrap(rightCell, li.isVoided()) + "</td></tr>"
                    + "</table></html>";
            setText(row);
            if (li.isVoided() && !isSelected) {
                setForeground(Color.GRAY);
            }
        }
        return c;
    }

    private static String wrap(String content, boolean voided) {
        return voided ? "<strike>" + content + "</strike>" : content;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
