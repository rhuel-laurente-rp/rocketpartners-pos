package com.rocketpartners.onboarding.possystem.display;

import org.junit.jupiter.api.Test;

import javax.swing.JTextField;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural tests for {@link OnScreenKeypad}. No geometry assertions — key positions and sizes
 * are not exercised. These run headless: they mutate a plain field's document through the keys and
 * assert the resulting text, plus the load-bearing focusability contract.
 */
class OnScreenKeypadTest {

    private static JTextField field() {
        return new JTextField();
    }

    // ---- Insertion --------------------------------------------------------

    @Test
    void tappingADigit_insertsItAtTheCaret() {
        JTextField f = field();
        f.setText("12");
        f.setCaretPosition(1);
        OnScreenKeypad keypad = new OnScreenKeypad(f, false);

        keypad.getKeyForTest("9").doClick();

        assertThat(f.getText()).isEqualTo("192");
    }

    @Test
    void tappingADigit_replacesTheCurrentSelection() {
        JTextField f = field();
        f.setText("500");
        f.selectAll();
        OnScreenKeypad keypad = new OnScreenKeypad(f, false);

        keypad.getKeyForTest("7").doClick();

        // Selection replaced, not appended — a cashier changing 500 to 7 must not end up with 5007.
        assertThat(f.getText()).isEqualTo("7");
    }

    // ---- Backspace / clear ------------------------------------------------

    @Test
    void backspace_deletesAtTheCaret() {
        JTextField f = field();
        f.setText("12");
        f.setCaretPosition(2);
        OnScreenKeypad keypad = new OnScreenKeypad(f, false);

        keypad.getKeyForTest(OnScreenKeypad.BACKSPACE_LABEL).doClick();

        assertThat(f.getText()).isEqualTo("1");
    }

    @Test
    void clear_emptiesTheField() {
        JTextField f = field();
        f.setText("999");
        OnScreenKeypad keypad = new OnScreenKeypad(f, false);

        keypad.getKeyForTest(OnScreenKeypad.CLEAR_LABEL).doClick();

        assertThat(f.getText()).isEmpty();
    }

    // ---- Decimal presence -------------------------------------------------

    @Test
    void moneyKeypad_hasADecimalKey_thatInsertsADecimalPoint() {
        JTextField f = field();
        OnScreenKeypad keypad = new OnScreenKeypad(f, true);

        assertThat(keypad.hasDecimalKeyForTest()).isTrue();
        keypad.getKeyForTest("1").doClick();
        keypad.getKeyForTest(OnScreenKeypad.DECIMAL_LABEL).doClick();
        keypad.getKeyForTest("5").doClick();
        assertThat(f.getText()).isEqualTo("1.5");
    }

    @Test
    void quantityKeypad_hasNoDecimalKeyAtAll() {
        OnScreenKeypad keypad = new OnScreenKeypad(field(), false);

        assertThat(keypad.hasDecimalKeyForTest()).isFalse();
        assertThat(keypad.getKeyForTest(OnScreenKeypad.DECIMAL_LABEL)).isNull();
    }

    // ---- Retargeting ------------------------------------------------------

    @Test
    void setTarget_redirectsInsertionToTheNewField() {
        JTextField first = field();
        JTextField second = field();
        OnScreenKeypad keypad = new OnScreenKeypad(first, false);

        keypad.setTarget(second);
        keypad.getKeyForTest("4").doClick();

        assertThat(first.getText()).isEmpty();
        assertThat(second.getText()).isEqualTo("4");
        assertThat(keypad.getTarget()).isSameAs(second);
    }

    @Test
    void setTarget_redirectsClearAndBackspaceToo_notJustDigits() {
        // Regression: Clear/backspace are wired in the constructor, where the parameter `target`
        // shadows the field. They must read the FIELD so they follow setTarget like the digit keys,
        // rather than staying frozen on the field the keypad was constructed with.
        JTextField first = field();
        first.setText("111");
        JTextField second = field();
        second.setText("222");
        OnScreenKeypad keypad = new OnScreenKeypad(first, false);

        keypad.setTarget(second);
        second.setCaretPosition(second.getText().length()); // caret is at 0 after setText off-screen
        keypad.getKeyForTest(OnScreenKeypad.BACKSPACE_LABEL).doClick();
        assertThat(first.getText()).isEqualTo("111"); // untouched
        assertThat(second.getText()).isEqualTo("22");  // backspaced

        keypad.getKeyForTest(OnScreenKeypad.CLEAR_LABEL).doClick();
        assertThat(first.getText()).isEqualTo("111"); // still untouched
        assertThat(second.getText()).isEmpty();         // cleared
    }

    // ---- Advance key ------------------------------------------------------

    @Test
    void advanceKey_isAbsent_whenNoOnNextProvided() {
        OnScreenKeypad keypad = new OnScreenKeypad(field(), false);

        assertThat(keypad.hasNextKeyForTest()).isFalse();
        assertThat(keypad.getKeyForTest(OnScreenKeypad.NEXT_LABEL)).isNull();
    }

    @Test
    void advanceKey_isPresentAndInvokesCallback_whenOnNextProvided() {
        AtomicInteger fired = new AtomicInteger();
        OnScreenKeypad keypad = new OnScreenKeypad(field(), false, fired::incrementAndGet);

        assertThat(keypad.hasNextKeyForTest()).isTrue();
        keypad.getKeyForTest(OnScreenKeypad.NEXT_LABEL).doClick();
        assertThat(fired.get()).isEqualTo(1);
    }

    // ---- Focusability -----------------------------------------------------

    @Test
    void noKeyIsFocusable_onEitherConfiguration() {
        for (boolean decimal : new boolean[]{true, false}) {
            OnScreenKeypad keypad = new OnScreenKeypad(field(), decimal);
            for (PosButton key : keypad.getKeysForTest()) {
                assertThat(key.isFocusable())
                        .as("key \"%s\" must not be focusable — a focusable key would steal focus "
                                + "from the target field", key.getText())
                        .isFalse();
            }
        }
    }

    @Test
    void advanceKey_isNotFocusable() {
        OnScreenKeypad keypad = new OnScreenKeypad(field(), false, () -> { });

        for (PosButton key : keypad.getKeysForTest()) {
            assertThat(key.isFocusable())
                    .as("key \"%s\" must not be focusable", key.getText())
                    .isFalse();
        }
    }
}
