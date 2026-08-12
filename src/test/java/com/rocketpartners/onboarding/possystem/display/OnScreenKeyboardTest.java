package com.rocketpartners.onboarding.possystem.display;

import org.junit.jupiter.api.Test;

import javax.swing.JTextField;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural tests for {@link OnScreenKeyboard}. No geometry assertions. Runs headless.
 */
class OnScreenKeyboardTest {

    private static JTextField field() {
        return new JTextField();
    }

    private static OnScreenKeyboard keyboardOn(JTextField f) {
        return new OnScreenKeyboard(f, () -> { });
    }

    @Test
    void tappingALetter_insertsLowercaseAtTheCaret() {
        JTextField f = field();
        f.setText("co");
        f.setCaretPosition(2);
        OnScreenKeyboard kb = keyboardOn(f);

        kb.getKeyForTest("l").doClick();
        kb.getKeyForTest("a").doClick();

        assertThat(f.getText()).isEqualTo("cola");
    }

    @Test
    void tappingANumberRowKey_insertsTheDigit() {
        JTextField f = field();
        OnScreenKeyboard kb = keyboardOn(f);

        kb.getKeyForTest("2").doClick();
        kb.getKeyForTest("0").doClick();

        assertThat(f.getText()).isEqualTo("20");
    }

    @Test
    void spaceKey_insertsASpace() {
        JTextField f = field();
        OnScreenKeyboard kb = keyboardOn(f);

        kb.getKeyForTest("a").doClick();
        kb.getKeyForTest(OnScreenKeyboard.SPACE_LABEL).doClick();
        kb.getKeyForTest("b").doClick();

        assertThat(f.getText()).isEqualTo("a b");
    }

    @Test
    void doneKey_runsTheDismissCallback() {
        AtomicInteger done = new AtomicInteger();
        OnScreenKeyboard kb = new OnScreenKeyboard(field(), done::incrementAndGet);

        kb.getDoneKeyForTest().doClick();

        assertThat(done.get()).isEqualTo(1);
    }

    @Test
    void noKeyIsFocusable() {
        OnScreenKeyboard kb = keyboardOn(field());
        for (PosButton key : kb.getKeysForTest()) {
            assertThat(key.isFocusable())
                    .as("key \"%s\" must not be focusable", key.getText())
                    .isFalse();
        }
    }

    @Test
    void keysRouteThroughTheDocumentFilter_soAFilterStillRejectsWhatItRejected() {
        // Bind the keyboard to a field guarded by a digit-only filter. A letter key must not be
        // able to smuggle a letter past the filter — proof that keys insert through the Document
        // rather than around it. The digit keys on the number row still get through.
        JTextField f = field();
        ((AbstractDocument) f.getDocument()).setDocumentFilter(new DigitsOnly());
        OnScreenKeyboard kb = keyboardOn(f);

        kb.getKeyForTest("a").doClick();
        assertThat(f.getText()).as("letter rejected by the field's filter").isEmpty();

        kb.getKeyForTest("7").doClick();
        assertThat(f.getText()).as("digit still allowed").isEqualTo("7");
    }

    /** Rejects any insertion/replacement that isn't all digits. */
    private static final class DigitsOnly extends DocumentFilter {
        @Override public void insertString(FilterBypass fb, int off, String s, AttributeSet a)
                throws BadLocationException {
            if (allDigits(s)) super.insertString(fb, off, s, a);
        }
        @Override public void replace(FilterBypass fb, int off, int len, String s, AttributeSet a)
                throws BadLocationException {
            if (allDigits(s)) super.replace(fb, off, len, s, a);
        }
        private static boolean allDigits(String s) {
            if (s == null || s.isEmpty()) return true;
            for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
            return true;
        }
    }
}
