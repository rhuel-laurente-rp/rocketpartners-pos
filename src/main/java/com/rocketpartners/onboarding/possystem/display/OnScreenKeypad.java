package com.rocketpartners.onboarding.possystem.display;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * A touch numeric keypad that types into a target text field. Built for the money and quantity
 * entry dialogs, where the terminal is touch-operated and no physical keyboard is assumed.
 *
 * <p><strong>Layout.</strong> A 3-column grid — {@code 1}–{@code 9} over three rows, then a
 * bottom row of {@code Clear}, {@code 0}, and backspace. When constructed {@link #OnScreenKeypad(
 * JTextComponent, boolean) with a decimal point}, a {@code .} key joins the bottom row (money
 * entry); when constructed without one (quantity entry) the key is <em>absent</em>, not disabled
 * — a dead key on a keypad reads as broken hardware.</p>
 *
 * <p><strong>Every key is {@link javax.swing.JComponent#setFocusable(boolean) non-focusable}.</strong>
 * This is the load-bearing constraint. If a key took focus, the target field would lose it — the
 * caret would vanish, {@code selectAll()} priming would break, and the focus-driven
 * {@code PERSIST} validation in {@link ChangeQuantityView} and {@link PayWithCashView} would stop
 * behaving as tested. The field keeps focus through every keypress.</p>
 *
 * <p><strong>Input flows through the field's {@link javax.swing.text.Document}, never through
 * synthesised key events.</strong> Two reasons. First, it cannot collide with the application-wide
 * {@link java.awt.KeyEventDispatcher} that {@link ScannerViewController} uses to capture scanner
 * bursts — a document mutation is invisible to that dispatcher. Second, every {@link
 * javax.swing.text.DocumentFilter} already installed on the field keeps applying: the digit-only,
 * single-decimal-point, and length-cap rules are inherited rather than re-implemented, so a key
 * can never smuggle a character the field would have rejected from the keyboard. Digit and decimal
 * keys go in via {@link JTextComponent#replaceSelection(String)} (which respects the current
 * selection and the document filter); backspace deletes at the caret; clear empties the field —
 * all through the document.</p>
 *
 * <p><strong>Dumb by construction.</strong> The keypad holds a reference to its target field and
 * nothing else. No validation, no formatting, no {@code TransactionService}, no knowledge of what
 * the number means. Keys are built once in the constructor; {@code paintComponent} allocates
 * nothing new.</p>
 */
public class OnScreenKeypad extends JPanel {

    /** Minimum touch size of every key, in pixels. Generous because there are few keys. */
    static final int KEY_SIZE = 56;

    /** Backspace glyph. A symbol rather than a word — the labelled control keys are Clear/0. */
    static final String BACKSPACE_LABEL = "⌫";
    static final String CLEAR_LABEL = "Clear";
    static final String DECIMAL_LABEL = ".";

    private final JTextComponent target;
    private final boolean withDecimal;
    private final List<PosButton> keys = new ArrayList<>();

    /**
     * @param target      the field this keypad types into; must not be {@code null}
     * @param withDecimal {@code true} to include a decimal-point key (money), {@code false} to
     *                    omit it entirely (quantity)
     */
    public OnScreenKeypad(JTextComponent target, boolean withDecimal) {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        this.target = target;
        this.withDecimal = withDecimal;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        add(row(digitKey("1"), digitKey("2"), digitKey("3")));
        add(gap());
        add(row(digitKey("4"), digitKey("5"), digitKey("6")));
        add(gap());
        add(row(digitKey("7"), digitKey("8"), digitKey("9")));
        add(gap());

        PosButton clear = controlKey(CLEAR_LABEL);
        clear.addActionListener(e -> OnScreenKeys.clear(target));
        PosButton backspace = controlKey(BACKSPACE_LABEL);
        backspace.addActionListener(e -> OnScreenKeys.backspace(target));
        if (withDecimal) {
            add(row(clear, digitKey("0"), digitKey(DECIMAL_LABEL), backspace));
        } else {
            add(row(clear, digitKey("0"), backspace));
        }
    }

    // ---- Key construction --------------------------------------------------

    /** A digit (or decimal) key: neutral surface fill, elevated like a physical key. Typing the
     *  key's own label into the field is all it does. */
    private PosButton digitKey(String label) {
        PosButton b = new PosButton(label, PosTheme.SURFACE, PosTheme.INK,
                PosTheme.base(Font.BOLD, PosTheme.AMOUNT));
        b.addActionListener(e -> OnScreenKeys.insert(target, label));
        return sizeKey(b);
    }

    /** A control key (Clear / backspace): reads as secondary. Listener wired by the caller. */
    private PosButton controlKey(String label) {
        return sizeKey(PosButtons.secondary(label));
    }

    private PosButton sizeKey(PosButton b) {
        b.setFocusable(false);
        b.setTouchMinHeight(KEY_SIZE);
        b.setPreferredSize(new Dimension(KEY_SIZE, KEY_SIZE + PosButton.SHADOW_INSET));
        keys.add(b);
        return b;
    }

    /** One row of keys, stretched to fill the keypad width so rows line up edge to edge. */
    private static JPanel row(PosButton... rowKeys) {
        JPanel row = new JPanel(new java.awt.GridLayout(1, rowKeys.length, PosTheme.BUTTON_GAP, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, KEY_SIZE + PosButton.SHADOW_INSET));
        for (PosButton k : rowKeys) row.add(k);
        return row;
    }

    private static Component gap() {
        return Box.createVerticalStrut(PosTheme.BUTTON_GAP);
    }

    // ---- Test hooks --------------------------------------------------------

    /** For tests: every key in the keypad, in construction order. */
    List<PosButton> getKeysForTest() {
        return List.copyOf(keys);
    }

    /** For tests: the first key whose label matches, or {@code null} if none. */
    PosButton getKeyForTest(String label) {
        for (PosButton b : keys) {
            if (label.equals(b.getText())) return b;
        }
        return null;
    }

    /** For tests: whether this keypad rendered a decimal-point key at all. */
    boolean hasDecimalKeyForTest() {
        return getKeyForTest(DECIMAL_LABEL) != null;
    }
}
