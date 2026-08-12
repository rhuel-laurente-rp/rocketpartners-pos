package com.rocketpartners.onboarding.possystem.display;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * A touch QWERTY keyboard that types into a target text field. Built for the Quick Add search
 * field, which is the fallback when a barcode won't scan on a keyboard-less touch terminal.
 *
 * <p><strong>Layout.</strong> A number row ({@code 1}–{@code 0}), three letter rows
 * ({@code qwertyuiop} / {@code asdfghjkl} / {@code zxcvbnm}), then a bottom row of {@code Space},
 * backspace, {@code Clear}, and {@code Done}. The number row is present because product searches
 * carry digits ({@code 20Z}, {@code 1.74oz}); a mode toggle would just add a tap to a fallback
 * flow. There is <em>no</em> shift or caps key — search matches case-insensitively, so a case
 * control would change nothing — so the letters are lower-case and type lower-case.</p>
 *
 * <p><strong>Every key is {@link javax.swing.JComponent#setFocusable(boolean) non-focusable}</strong>
 * and every keystroke flows through the field's {@link javax.swing.text.Document} rather than a
 * synthesised {@link java.awt.event.KeyEvent} — the same two contracts {@link OnScreenKeypad}
 * documents, and for the same reasons: the target field keeps focus, the on-screen typing can't be
 * mistaken for a scanner burst by the global {@link java.awt.KeyEventDispatcher}, and any {@link
 * javax.swing.text.DocumentFilter} on the field keeps applying.</p>
 *
 * <p><strong>Done dismisses.</strong> The Done key runs the dismissal callback handed to the
 * constructor; the keyboard itself holds no opinion about where it lives or when else it should
 * hide — its owner ({@link QuickAddPanel}) wires the other triggers.</p>
 *
 * <p><strong>Dumb by construction.</strong> Holds a reference to its target field and a dismissal
 * {@link Runnable}, nothing else. Keys are built once in the constructor.</p>
 */
public class OnScreenKeyboard extends JPanel {

    /** Minimum touch height of every key, in pixels. Comfortably wide at the panel's width. */
    static final int KEY_HEIGHT = 44;

    static final String BACKSPACE_LABEL = "⌫";
    static final String CLEAR_LABEL = "Clear";
    static final String SPACE_LABEL = "Space";
    static final String DONE_LABEL = "Done";

    private static final String[] ROWS = {
            "1234567890",
            "qwertyuiop",
            "asdfghjkl",
            "zxcvbnm",
    };

    private final JTextComponent target;
    private final Runnable onDone;
    private final List<PosButton> keys = new ArrayList<>();

    /**
     * @param target the field this keyboard types into; must not be {@code null}
     * @param onDone  invoked when the Done key is tapped; must not be {@code null}
     */
    public OnScreenKeyboard(JTextComponent target, Runnable onDone) {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        if (onDone == null) throw new IllegalArgumentException("onDone must not be null");
        this.target = target;
        this.onDone = onDone;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        for (String rowChars : ROWS) {
            PosButton[] rowKeys = new PosButton[rowChars.length()];
            for (int i = 0; i < rowChars.length(); i++) {
                rowKeys[i] = charKey(String.valueOf(rowChars.charAt(i)));
            }
            add(row(rowKeys));
            add(gap());
        }

        PosButton space = controlKey(SPACE_LABEL);
        space.addActionListener(e -> OnScreenKeys.insert(target, " "));
        PosButton backspace = controlKey(BACKSPACE_LABEL);
        backspace.addActionListener(e -> OnScreenKeys.backspace(target));
        PosButton clear = controlKey(CLEAR_LABEL);
        clear.addActionListener(e -> OnScreenKeys.clear(target));
        PosButton done = PosButtons.primary(DONE_LABEL);
        sizeKey(done);
        done.addActionListener(e -> onDone.run());
        add(row(space, backspace, clear, done));
    }

    // ---- Key construction --------------------------------------------------

    /** A letter or digit key. Typing its own label into the field is all it does. */
    private PosButton charKey(String label) {
        PosButton b = new PosButton(label, PosTheme.SURFACE, PosTheme.INK,
                PosTheme.base(Font.BOLD, PosTheme.ROW));
        b.addActionListener(e -> OnScreenKeys.insert(target, label));
        return sizeKey(b);
    }

    /** A control key (Space / backspace / Clear): reads as secondary. Listener wired by caller. */
    private PosButton controlKey(String label) {
        return sizeKey(PosButtons.secondary(label));
    }

    private PosButton sizeKey(PosButton b) {
        b.setFocusable(false);
        b.setTouchMinHeight(KEY_HEIGHT);
        b.setPreferredSize(new Dimension(KEY_HEIGHT, KEY_HEIGHT + PosButton.SHADOW_INSET));
        keys.add(b);
        return b;
    }

    /** One row of keys, stretched to fill the keyboard width. */
    private static JPanel row(PosButton... rowKeys) {
        JPanel row = new JPanel(new GridLayout(1, rowKeys.length, PosTheme.BUTTON_GAP, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, KEY_HEIGHT + PosButton.SHADOW_INSET));
        for (PosButton k : rowKeys) row.add(k);
        return row;
    }

    private static Component gap() {
        return Box.createVerticalStrut(PosTheme.BUTTON_GAP);
    }

    // ---- Test hooks --------------------------------------------------------

    /** For tests: every key on the keyboard, in construction order. */
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

    /** For tests: the Done key. */
    PosButton getDoneKeyForTest() {
        return getKeyForTest(DONE_LABEL);
    }
}
