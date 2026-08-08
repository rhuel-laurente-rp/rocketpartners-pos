package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JFormattedTextField;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Dialog-level tests for the new {@link PayWithCashView}. Exercises the money input filter,
 * the PERSIST regression the class Javadoc calls out, footer button parity with
 * {@link ChangeQuantityView}, live change strip, and the "typing immediately replaces the
 * prefill" rule.
 */
class PayWithCashViewTest {

    private RecordingDispatcher dispatcher;
    private PayWithCashView view;

    @BeforeEach
    void setUp() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        dispatcher = new RecordingDispatcher();
        view = new PayWithCashView(null, dispatcher);
    }

    // ---- DocumentFilter --------------------------------------------------

    @Test
    void editor_rejectsLettersAndMinusAndSecondDecimal_onKeystroke() throws Exception {
        openWith("7.30", "7.30");
        JFormattedTextField field = view.getCashFieldForTest();

        SwingUtilities.invokeAndWait(() -> field.setText(""));
        typeInto(field, "a");
        typeInto(field, "-");
        typeInto(field, "!");
        assertThat(field.getText()).isEmpty();

        typeInto(field, "7");
        typeInto(field, ".");
        typeInto(field, "3");
        typeInto(field, "0");
        assertThat(field.getText()).isEqualTo("7.30");

        // A second decimal must be rejected.
        typeInto(field, ".");
        assertThat(field.getText()).isEqualTo("7.30");

        // Three digits past the point rejected.
        typeInto(field, "5");
        assertThat(field.getText()).isEqualTo("7.30");
    }

    @Test
    void editor_rejectsPastesWithLettersOrTooManyDecimals_wholesale() throws Exception {
        openWith("7.30", "7.30");
        JFormattedTextField field = view.getCashFieldForTest();

        SwingUtilities.invokeAndWait(() -> field.setText(""));
        pasteInto(field, "12abc");
        assertThat(field.getText()).isEmpty();

        pasteInto(field, "-5.00");
        assertThat(field.getText()).isEmpty();

        pasteInto(field, "3.141");
        assertThat(field.getText()).isEmpty();

        pasteInto(field, "20.00");
        assertThat(field.getText()).isEqualTo("20.00");
    }

    // ---- PERSIST focus-lost behaviour ------------------------------------

    @Test
    void editor_focusLostBehaviour_isPersist() {
        // Load-bearing: under the default COMMIT_OR_REVERT, an invalid entry silently reverts
        // to the last valid amount when Confirm moves focus off the field, and the confirm
        // handler tenders that reverted amount — wrong payment, real change to the customer.
        JFormattedTextField field = view.getCashFieldForTest();
        assertThat(field.getFocusLostBehavior()).isEqualTo(JFormattedTextField.PERSIST);
    }

    @Test
    void confirmClick_afterUnderpayment_showsInlineMessageAndDispatchesNoConfirmEvent()
            throws Exception {
        // The PERSIST regression test the brief calls for: this must exercise a real focus
        // transfer via doClick, not a setText/read-value shortcut.
        openWith("7.30", "7.30");
        JFormattedTextField field = view.getCashFieldForTest();
        SwingUtilities.invokeAndWait(() -> field.setText("5.00"));

        clickConfirm();

        assertThat(dispatcher.eventsOf(PosEventType.CASH_CONFIRM_PRESSED))
                .as("underpayment must not dispatch a confirm event — validation is inline")
                .isEmpty();
        assertThat(view.getValidationMessageForTest().getText()).isNotBlank();
        assertThat(view.getValidationMessageForTest().getText().trim()).isNotEmpty();
        assertThat(view.getValidationMessageForTest().getForeground()).isEqualTo(PosTheme.STOP);
    }

    @Test
    void emptyField_onConfirm_showsInlineMessage_noConfirmEventDispatched() throws Exception {
        openWith("7.30", "7.30");
        JFormattedTextField field = view.getCashFieldForTest();
        SwingUtilities.invokeAndWait(() -> field.setText(""));

        clickConfirm();

        assertThat(dispatcher.eventsOf(PosEventType.CASH_CONFIRM_PRESSED)).isEmpty();
        assertThat(view.getValidationMessageForTest().getText().trim()).isNotEmpty();
    }

    // ---- Confirm success flow --------------------------------------------

    @Test
    void confirmClick_overpayment_dispatchesConfirmWithRawText() throws Exception {
        openWith("7.30", "7.30");
        JFormattedTextField field = view.getCashFieldForTest();
        SwingUtilities.invokeAndWait(() -> field.setText("20.00"));

        clickConfirm();

        List<PosEvent> confirms = dispatcher.eventsOf(PosEventType.CASH_CONFIRM_PRESSED);
        assertThat(confirms).hasSize(1);
        assertThat(confirms.get(0).getProperty("cashReceived", String.class)).isEqualTo("20.00");
    }

    // ---- Prefill / selectAll -------------------------------------------

    @Test
    void openFor_prefillsField_andSelectsAll_soFirstKeystrokeReplacesIt() throws Exception {
        openWith("7.30", "7.30");
        JFormattedTextField field = view.getCashFieldForTest();

        assertThat(field.getText()).isEqualTo("7.30");
        // Selection covers the whole prefill: a keystroke replaces it wholesale.
        assertThat(field.getSelectionStart()).isZero();
        assertThat(field.getSelectionEnd()).isEqualTo(field.getText().length());
    }

    // ---- Live status strip (change-due / error) ------------------------

    @Test
    void statusStrip_showsError_whenEnteredAmountIsBelowAmountDue() throws Exception {
        openWith("7.30", "7.30");
        JFormattedTextField field = view.getCashFieldForTest();
        SwingUtilities.invokeAndWait(() -> field.setText("5.00"));

        assertThat(view.getStatusLineForTest().getText())
                .containsIgnoringCase("less than");
        assertThat(view.getStatusLineForTest().getForeground()).isEqualTo(PosTheme.STOP);
    }

    @Test
    void statusStrip_showsChangeDue_whenEnteredAmountIsAboveAmountDue() throws Exception {
        openWith("7.30", "7.30");
        JFormattedTextField field = view.getCashFieldForTest();
        SwingUtilities.invokeAndWait(() -> field.setText("20.00"));

        assertThat(view.getStatusLineForTest().getText()).contains("$12.70");
        assertThat(view.getStatusLineForTest().getForeground()).isEqualTo(PosTheme.GO);
    }

    // ---- Footer button parity -------------------------------------------

    @Test
    void confirmAndCancel_reportIdenticalSizes() {
        assertThat(view.getConfirmButtonForTest().getPreferredSize())
                .isEqualTo(view.getCancelButtonForTest().getPreferredSize());
    }

    // ---- Title/header parity --------------------------------------------

    @Test
    void nativeWindowTitle_isEmpty_soTitleAppearsExactlyOnce() {
        assertThat(view.getTitle()).isEmpty();
        assertThat(view.getHeaderTitleLabelForTest().getText()).isEqualTo("Cash Payment");
    }

    @Test
    void header_showsAmountDue_afterOpenFor() throws Exception {
        openWith("7.30", "7.30");
        assertThat(view.getHeaderAmountLabelForTest()).isNotNull();
        assertThat(view.getHeaderAmountLabelForTest().getText()).isEqualTo("$7.30");
    }

    // ---- Cancel ---------------------------------------------------------

    @Test
    void cancelClick_dispatchesCancelEvent() throws Exception {
        openWith("7.30", "7.30");
        SwingUtilities.invokeAndWait(() -> view.getCancelButtonForTest().doClick());
        assertThat(dispatcher.eventsOf(PosEventType.CASH_CANCEL_PRESSED)).hasSize(1);
        assertThat(dispatcher.eventsOf(PosEventType.CASH_CONFIRM_PRESSED)).isEmpty();
    }

    // ---- helpers --------------------------------------------------------

    private void openWith(String amountDue, String prefill) throws Exception {
        // grandTotalAmountDue IS the prefill under the new semantics; the two-arg helper is
        // kept only to minimise churn in the test bodies below. Both strings should match.
        assert amountDue.equals(prefill) : "grandTotalAmountDue == prefill under new semantics";
        BigDecimal due = new BigDecimal(amountDue);
        SwingUtilities.invokeAndWait(() -> view.openFor(due, PayWithCashView.Mode.EXACT));
    }

    private void clickConfirm() throws Exception {
        SwingUtilities.invokeAndWait(() -> view.getConfirmButtonForTest().doClick());
    }

    private static void typeInto(JFormattedTextField editor, String s) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                editor.getDocument().insertString(
                        editor.getDocument().getLength(), s, null);
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        });
    }

    private static void pasteInto(JFormattedTextField editor, String s) throws Exception {
        AtomicReference<Boolean> pasted = new AtomicReference<>(false);
        SwingUtilities.invokeAndWait(() -> {
            try {
                Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                cb.setContents(new StringSelection(s), null);
                editor.selectAll();
                editor.paste();
                pasted.set(true);
            } catch (IllegalStateException ex) {
                pasted.set(false);
            }
        });
        assumeFalse(!pasted.get(), "system clipboard unavailable");
    }

    static final class RecordingDispatcher implements IPosEventDispatcher {
        final List<PosEvent> events = new ArrayList<>();

        @Override
        public void dispatchPosEvent(PosEvent event) {
            events.add(event);
        }

        List<PosEvent> eventsOf(PosEventType type) {
            return events.stream().filter(e -> e.getType() == type).toList();
        }
    }
}
