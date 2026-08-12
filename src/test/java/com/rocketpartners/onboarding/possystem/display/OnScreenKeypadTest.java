package com.rocketpartners.onboarding.possystem.display;

import org.junit.jupiter.api.Test;

import javax.swing.JTextField;

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
}
