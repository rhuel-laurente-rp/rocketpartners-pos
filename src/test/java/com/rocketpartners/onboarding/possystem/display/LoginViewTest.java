package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.component.Journal;
import com.rocketpartners.onboarding.possystem.component.JournalRecord;
import org.junit.jupiter.api.Test;

import javax.swing.text.AbstractDocument;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Behavioural tests for {@link LoginView}. No geometry assertions. The tests that build the
 * {@link javax.swing.JFrame} are skipped in headless CI (the frame needs a display); the
 * image-loading and null-panel cases run everywhere.
 */
class LoginViewTest {

    private CapturingJournal journal;
    private AtomicReference<String> loggedInOperator;
    private LoginView view;

    private LoginView newLogin() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        journal = new CapturingJournal();
        loggedInOperator = new AtomicReference<>();
        view = new LoginView(journal, "Rocket Store", 7, loggedInOperator::set);
        return view;
    }

    // ---- Proceed / fail ---------------------------------------------------

    @Test
    void correctDemoCredentials_proceed_andDisposeTheWindow() {
        LoginView v = newLogin();
        v.pack(); // realise the peer so dispose() is observable via isDisplayable()
        assertThat(v.isDisplayable()).isTrue();

        // Both fields are pre-filled with the demo pair, so a click straight through logs in.
        v.getLoginButtonForTest().doClick();

        assertThat(loggedInOperator.get()).isEqualTo(DemoCredentials.OPERATOR_ID);
        assertThat(v.isDisplayable()).isFalse();
    }

    @Test
    void wrongOperatorId_showsTheMessage_andDoesNotProceed() {
        LoginView v = newLogin();
        v.getOperatorFieldForTest().setText("9999");

        v.getLoginButtonForTest().doClick();

        assertThat(v.getMessageLabelForTest().getText()).isEqualTo(LoginView.INCORRECT_MESSAGE);
        assertThat(loggedInOperator.get()).isNull();
    }

    @Test
    void wrongPin_showsTheSameMessage_andDoesNotProceed() {
        LoginView v = newLogin();
        v.getPinFieldForTest().setText("9999");

        v.getLoginButtonForTest().doClick();

        assertThat(v.getMessageLabelForTest().getText()).isEqualTo(LoginView.INCORRECT_MESSAGE);
        assertThat(loggedInOperator.get()).isNull();
    }

    @Test
    void eitherFieldEmpty_showsTheMessage() {
        LoginView v = newLogin();

        v.getOperatorFieldForTest().setText("");
        v.getLoginButtonForTest().doClick();
        assertThat(v.getMessageLabelForTest().getText()).isEqualTo(LoginView.INCORRECT_MESSAGE);
        assertThat(loggedInOperator.get()).isNull();

        // And the other field empty, same message.
        v.getOperatorFieldForTest().setText(DemoCredentials.OPERATOR_ID);
        v.getPinFieldForTest().setText("");
        v.getLoginButtonForTest().doClick();
        assertThat(v.getMessageLabelForTest().getText()).isEqualTo(LoginView.INCORRECT_MESSAGE);
        assertThat(loggedInOperator.get()).isNull();
    }

    @Test
    void message_clearsOnTheNextInput() {
        LoginView v = newLogin();
        v.getOperatorFieldForTest().setText("9999");
        v.getLoginButtonForTest().doClick();
        assertThat(v.getMessageLabelForTest().getText()).isEqualTo(LoginView.INCORRECT_MESSAGE);

        // Any edit clears the error.
        v.getOperatorFieldForTest().setText("1");

        assertThat(v.getMessageLabelForTest().getText().trim()).isEmpty();
    }

    @Test
    void noFailurePath_opensAModal() {
        LoginView v = newLogin();
        int windowsBefore = java.awt.Window.getWindows().length;

        v.getOperatorFieldForTest().setText("9999");
        v.getLoginButtonForTest().doClick();
        v.getPinFieldForTest().setText("");
        v.getLoginButtonForTest().doClick();

        // A failed attempt paints an inline message only — it must not spawn a dialog window.
        assertThat(java.awt.Window.getWindows().length).isEqualTo(windowsBefore);
    }

    // ---- Digit-only filter ------------------------------------------------

    @Test
    void nonDigitInput_isRejected_onBothFields_keystrokeAndPaste() throws Exception {
        LoginView v = newLogin();
        v.getOperatorFieldForTest().setText("");
        v.getPinFieldForTest().setText("");

        // Keystroke path (insertString): a lone non-digit is dropped.
        v.getOperatorFieldForTest().getDocument().insertString(0, "5", null);
        v.getOperatorFieldForTest().getDocument().insertString(1, "a", null);
        assertThat(v.getOperatorFieldForTest().getText()).isEqualTo("5");

        // Paste path (replace) — plain JTextField routes paste through the document, so the same
        // filter rejects a mixed run wholesale rather than stripping it.
        ((AbstractDocument) v.getPinFieldForTest().getDocument()).replace(0, 0, "9a9", null);
        assertThat(new String(v.getPinFieldForTest().getPassword())).isEmpty();
        ((AbstractDocument) v.getPinFieldForTest().getDocument()).replace(0, 0, "12", null);
        assertThat(new String(v.getPinFieldForTest().getPassword())).isEqualTo("12");
    }

    // ---- Keypad -----------------------------------------------------------

    @Test
    void keypadAdvance_onOperatorId_movesFocusToPin() {
        LoginView v = newLogin();
        v.getKeypadForTest().setTarget(v.getOperatorFieldForTest());

        v.getKeypadForTest().getKeyForTest(OnScreenKeypad.NEXT_LABEL).doClick();

        // The keypad now serves the PIN field — the observable proxy for "focus advanced to PIN".
        assertThat(v.getKeypadForTest().getTarget()).isSameAs(v.getPinFieldForTest());
        assertThat(loggedInOperator.get()).isNull();
    }

    @Test
    void keypadAdvance_onPin_advancesToTheLoginButton_withoutSubmitting() {
        LoginView v = newLogin();
        v.getKeypadForTest().setTarget(v.getPinFieldForTest());

        v.getKeypadForTest().getKeyForTest(OnScreenKeypad.NEXT_LABEL).doClick();

        // Enter never submits — it advances focus to the Login button. Even with the demo pair
        // pre-filled, no login has happened yet.
        assertThat(loggedInOperator.get()).isNull();

        // Submitting is exclusively the Login button.
        v.getLoginButtonForTest().doClick();
        assertThat(loggedInOperator.get()).isEqualTo(DemoCredentials.OPERATOR_ID);
    }

    @Test
    void tappingADigit_insertsIntoTheFocusedField_andThatFieldRetainsFocus() {
        LoginView v = newLogin();
        v.getPinFieldForTest().setText("");
        v.getKeypadForTest().setTarget(v.getPinFieldForTest());

        v.getKeypadForTest().getKeyForTest("7").doClick();

        assertThat(new String(v.getPinFieldForTest().getPassword())).isEqualTo("7");
        // Keys are non-focusable, so the target never changes out from under the cashier.
        assertThat(v.getKeypadForTest().getTarget()).isSameAs(v.getPinFieldForTest());
    }

    @Test
    void clearAndBackspace_applyToTheFocusedField_notAlwaysOperatorId() {
        // Reported bug: Clear / backspace only ever hit Operator ID, even with the PIN focused.
        LoginView v = newLogin();
        v.getOperatorFieldForTest().setText("1234");
        v.getPinFieldForTest().setText("5678");
        v.getKeypadForTest().setTarget(v.getPinFieldForTest());
        // Caret sits at 0 after setText on an unrealized field; put it at the end so backspace bites.
        v.getPinFieldForTest().setCaretPosition(v.getPinFieldForTest().getDocument().getLength());

        v.getKeypadForTest().getKeyForTest(OnScreenKeypad.BACKSPACE_LABEL).doClick();
        assertThat(v.getOperatorFieldForTest().getText()).isEqualTo("1234"); // untouched
        assertThat(new String(v.getPinFieldForTest().getPassword())).isEqualTo("567");

        v.getKeypadForTest().getKeyForTest(OnScreenKeypad.CLEAR_LABEL).doClick();
        assertThat(v.getOperatorFieldForTest().getText()).isEqualTo("1234"); // still untouched
        assertThat(new String(v.getPinFieldForTest().getPassword())).isEmpty();
    }

    @Test
    void noKeypadKeyIsFocusable() {
        LoginView v = newLogin();
        for (PosButton key : v.getKeypadForTest().getKeysForTest()) {
            assertThat(key.isFocusable())
                    .as("key \"%s\" must not be focusable", key.getText())
                    .isFalse();
        }
    }

    @Test
    void keypad_hasNoDecimalKey() {
        LoginView v = newLogin();
        assertThat(v.getKeypadForTest().hasDecimalKeyForTest()).isFalse();
    }

    // ---- Layout stability -------------------------------------------------

    @Test
    void formHeight_isIdentical_withAndWithoutMessage() {
        LoginView v = newLogin();
        int withoutMessage = v.getFormForTest().getPreferredSize().height;

        v.showMessageForTest();
        int withMessage = v.getFormForTest().getPreferredSize().height;

        assertThat(withMessage).isEqualTo(withoutMessage);
    }

    // ---- Journalling ------------------------------------------------------

    @Test
    void successfulLogin_producesAJournalEntry_withoutThePin() {
        LoginView v = newLogin();
        v.getLoginButtonForTest().doClick();

        JournalRecord success = journal.lastOf("LOGIN_SUCCEEDED");
        assertThat(success.getFields()).containsEntry("operator", DemoCredentials.OPERATOR_ID);
        assertThat(success.getStore()).isEqualTo("Rocket Store");
        assertThat(success.getLane()).isEqualTo(7);
        assertNoPinAnywhere(success);
    }

    @Test
    void failedLogin_producesAJournalEntry_withTheAttemptedId_withoutThePin() {
        LoginView v = newLogin();
        v.getOperatorFieldForTest().setText("4321");
        v.getLoginButtonForTest().doClick();

        JournalRecord failure = journal.lastOf("LOGIN_FAILED");
        assertThat(failure.getFields()).containsEntry("attemptedOperator", "4321");
        assertNoPinAnywhere(failure);
    }

    // ---- Image loading (headless-safe) ------------------------------------

    @Test
    void missingVectorResource_yieldsNull_ratherThanThrowing() {
        assertThat(LoginView.loadVector("/definitely-not-a-real-resource.png")).isNull();
    }

    @Test
    void vectorPanelWithNoImage_paintsAPlainPanel_ratherThanThrowing() {
        LoginView.VectorPanel panel = new LoginView.VectorPanel(null);
        panel.setSize(756, 982);
        assertThat(panel.hasImageForTest()).isFalse();

        BufferedImage img = new BufferedImage(756, 982, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            panel.paint(g); // must not throw despite the absent image
        } finally {
            g.dispose();
        }
    }

    @Test
    void bundledVectorResource_loads() {
        // Sanity: the packaged image is present, so the real screen shows it (the null case above
        // is the graceful fallback, not the norm).
        assertThat(LoginView.loadVector()).isNotNull();
    }

    // ---- Helpers ----------------------------------------------------------

    private static void assertNoPinAnywhere(JournalRecord record) {
        // No PIN key, and no field value equal to the PIN. (A substring scan of the rendered line
        // would be fragile — the demo PIN "0000" can collide with zeros in the ISO timestamp.)
        assertThat(record.getFields()).doesNotContainKey("pin");
        for (Object value : record.getFields().values()) {
            assertThat(String.valueOf(value)).isNotEqualTo(DemoCredentials.PIN);
        }
    }

    private static final class CapturingJournal implements Journal {
        final List<JournalRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void journal(JournalRecord record) {
            records.add(record);
        }

        JournalRecord lastOf(String event) {
            JournalRecord last = null;
            for (JournalRecord r : records) if (event.equals(r.getEvent())) last = r;
            assertThat(last).as("no record with event " + event).isNotNull();
            return last;
        }
    }
}
