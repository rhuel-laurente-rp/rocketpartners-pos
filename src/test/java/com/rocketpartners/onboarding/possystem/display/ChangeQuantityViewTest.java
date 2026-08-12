package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
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
 * Dialog-level tests for {@link ChangeQuantityView}. Exercises the input-hardening contract
 * that keeps a quantity of zero unreachable at the UI layer — the domain call
 * {@link com.rocketpartners.onboarding.commons.model.Transaction#updateLineItemQuantity(
 * LineItem, int)} throws below 1, and this dialog exists to make that impossible to trigger
 * from user input.
 */
class ChangeQuantityViewTest {

    private static final int MAX_QUANTITY = 999;
    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("1.00"));

    private RecordingDispatcher dispatcher;
    private ChangeQuantityView view;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        dispatcher = new RecordingDispatcher();
        SwingUtilities.invokeAndWait(() -> {
            view = new ChangeQuantityView(null, dispatcher, MAX_QUANTITY);
            // PosDialog is modal — setVisible(true) inside openFor(...) enters a nested
            // dispatch loop and blocks invokeAndWait forever, stalling the build on a live
            // dialog. Force non-modal for tests so the wiring assertions can inspect the
            // primed dialog state without a human closing the window.
            view.setModal(false);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (view != null) {
            SwingUtilities.invokeAndWait(() -> {
                view.setVisible(false);
                view.dispose();
            });
        }
    }

    // ---- DocumentFilter --------------------------------------------------

    @Test
    void editor_rejectsLettersSymbolsAndMinusOnKeystroke() throws Exception {
        JTextField editor = view.getSpinnerEditorForTest();

        editor.setText("");
        typeInto(editor, "a");
        typeInto(editor, "!");
        typeInto(editor, "-");
        typeInto(editor, ".");
        typeInto(editor, "+");

        assertThat(editor.getText()).isEmpty();

        typeInto(editor, "3");
        assertThat(editor.getText()).isEqualTo("3");
    }

    @Test
    void editor_stillRejectsInvalidCharacters_afterOpenFor() throws Exception {
        // JSpinner.setValue inside openFor() reinstalls the formatter, and every formatter's
        // install() overwrites the Document's DocumentFilter with its own permissive one. If
        // the view does not re-attach the digit filter on formatter change, letters and
        // symbols land on screen and are only rejected at Confirm time. Regression pin for
        // that: open the dialog, then try the same invalid characters that the "keystroke"
        // test above proves are rejected pre-open.
        openFor(3);
        JTextField editor = view.getSpinnerEditorForTest();
        SwingUtilities.invokeAndWait(() -> editor.setText(""));

        typeInto(editor, "a");
        typeInto(editor, "!");
        typeInto(editor, "-");
        typeInto(editor, ".");
        typeInto(editor, "+");

        assertThat(editor.getText())
                .as("filter must survive openFor's setValue-driven formatter reinstall")
                .isEmpty();

        typeInto(editor, "5");
        assertThat(editor.getText()).isEqualTo("5");
    }

    @Test
    void editor_rejectsMixedAndNonDigitPastes_wholesale() throws Exception {
        JTextField editor = view.getSpinnerEditorForTest();
        editor.setText("");

        pasteInto(editor, "12abc");
        assertThat(editor.getText())
                .as("partially-legal paste must be rejected wholesale rather than stripped")
                .isEmpty();

        pasteInto(editor, "-5");
        assertThat(editor.getText()).isEmpty();

        pasteInto(editor, "3.14");
        assertThat(editor.getText()).isEmpty();

        pasteInto(editor, "42");
        assertThat(editor.getText()).isEqualTo("42");
    }

    // ---- SpinnerNumberModel bounds ---------------------------------------

    @Test
    void model_refusesValuesBelowMinAndAboveMax() {
        // The spinner's model bounds are the runtime contract: nextValue/previousValue and the
        // formatted-text-field editor both consult these when converting user input, and any
        // future refactor that swaps the model out has to keep this shape.
        SpinnerNumberModel model = (SpinnerNumberModel) view.getSpinnerForTest().getModel();
        assertThat(((Number) model.getMinimum()).intValue()).isEqualTo(ChangeQuantityView.MIN_QUANTITY);
        assertThat(((Number) model.getMaximum()).intValue()).isEqualTo(MAX_QUANTITY);

        // Stepping past the bounds via the arrow-button API returns null — the model refuses
        // to move below MIN or above MAX rather than clamping silently.
        model.setValue(ChangeQuantityView.MIN_QUANTITY);
        assertThat(model.getPreviousValue()).isNull();
        model.setValue(MAX_QUANTITY);
        assertThat(model.getNextValue()).isNull();
    }

    // ---- commitEdit ------------------------------------------------------

    @Test
    void typedValueNotCommittedWithEnter_isStillAppliedOnConfirm() throws Exception {
        LineItem line = openFor(2);

        JTextField editor = view.getSpinnerEditorForTest();
        editor.setText("5");
        // Do NOT press Enter, do NOT call commitEdit ourselves — the dialog must do it. This
        // reproduces the "cashier types 5, clicks Confirm" flow.

        clickConfirm();

        PosEvent event = dispatcher.only(PosEventType.CHANGE_QTY_CONFIRM_PRESSED);
        assertThat(event.getProperty("lineItem", LineItem.class)).isSameAs(line);
        assertThat(event.getProperty("newQuantity", Integer.class)).isEqualTo(5);
    }

    // ---- Empty-field validation -----------------------------------------

    @Test
    void emptyField_onConfirm_keepsDialogOpenAndShowsInlineMessage() throws Exception {
        openFor(2);

        JTextField editor = view.getSpinnerEditorForTest();
        editor.setText("");
        clickConfirm();

        // Two observable signals that the dialog kept the cashier's attention: no confirm
        // event dispatched, and the inline STOP-coloured validation label is now showing.
        // We don't assert isVisible() on the dialog itself — modal dialogs in a headless-lite
        // JVM may or may not report visible depending on the WM, and this test needs to be
        // deterministic across CI environments.
        assertThat(dispatcher.eventsOf(PosEventType.CHANGE_QTY_CONFIRM_PRESSED))
                .as("no confirm event may be dispatched when the field is empty")
                .isEmpty();
        assertThat(view.getValidationMessageForTest().isVisible())
                .as("the inline STOP-coloured validation label must be shown")
                .isTrue();
        assertThat(view.getValidationMessageForTest().getText()).isNotBlank();
        assertThat(view.getValidationMessageForTest().getForeground()).isEqualTo(PosTheme.STOP);
    }

    // ---- Unchanged-quantity no-op ---------------------------------------

    @Test
    void unchangedQuantity_dispatchesNoEvent_andCloses() throws Exception {
        openFor(3);

        // Value already sits at 3; committing the same value must not dispatch.
        clickConfirm();

        assertThat(dispatcher.eventsOf(PosEventType.CHANGE_QTY_CONFIRM_PRESSED)).isEmpty();
        assertThat(view.isVisible()).isFalse();
    }

    // ---- Footer button sizes --------------------------------------------

    @Test
    void confirmAndCancel_reportIdenticalSizes() {
        assertThat(view.getConfirmButtonForTest().getPreferredSize())
                .isEqualTo(view.getCancelButtonForTest().getPreferredSize());
    }

    // ---- Cancel path -----------------------------------------------------

    @Test
    void cancel_dispatchesCancelEvent_notAQuantityChange() throws Exception {
        openFor(2);
        int cancelsBefore = dispatcher.eventsOf(PosEventType.CHANGE_QTY_CANCEL_PRESSED).size();

        clickCancel();

        // The only invariant that matters: no confirm event was dispatched (so the cashier's
        // intent — cancel — was honoured), and at least one cancel event fired (so the
        // controller closes the dialog and resumes scanner capture).
        assertThat(dispatcher.eventsOf(PosEventType.CHANGE_QTY_CONFIRM_PRESSED)).isEmpty();
        assertThat(dispatcher.eventsOf(PosEventType.CHANGE_QTY_CANCEL_PRESSED).size())
                .isGreaterThan(cancelsBefore);
    }

    // ---- The "no zero" invariant ----------------------------------------

    @Test
    void noPathInTheDialog_canProduceQuantityZero() throws Exception {
        openFor(4);

        // Attempt 1: type "0" into the editor and confirm. Either the spinner's editor bounces
        // the value back to a legal in-range number on commit, or the dialog's own >=1 guard
        // rejects it and shows the inline message. Whichever happens, the confirm event —
        // if any — must never carry a zero.
        JTextField editor = view.getSpinnerEditorForTest();
        editor.setText("0");
        clickConfirm();
        for (PosEvent e : dispatcher.eventsOf(PosEventType.CHANGE_QTY_CONFIRM_PRESSED)) {
            assertThat(e.getProperty("newQuantity", Integer.class))
                    .as("no confirm event may carry a zero quantity")
                    .isGreaterThanOrEqualTo(1);
        }

        // Attempt 2: try to move the spinner one step below its floor via the model's own
        // step API — the model refuses (returns null) rather than moving to zero.
        SpinnerNumberModel model = (SpinnerNumberModel) view.getSpinnerForTest().getModel();
        model.setValue(ChangeQuantityView.MIN_QUANTITY);
        assertThat(model.getPreviousValue())
                .as("stepping below MIN_QUANTITY must be refused, not clamped")
                .isNull();

        // Attempt 3: paste a minus-prefixed number. Digit-only filter rejects the whole paste,
        // and the value stays at whatever it was before.
        editor.setText("");
        pasteInto(editor, "-3");
        assertThat(editor.getText()).isEmpty();
    }

    // ---- On-screen keypad -------------------------------------------------

    @Test
    void keypad_isPresent_withNoDecimalKeyAtAll() throws Exception {
        openFor(2);
        assertThat(view.getKeypadForTest()).isNotNull();
        assertThat(view.getKeypadForTest().hasDecimalKeyForTest())
                .as("a quantity keypad must not carry a decimal-point key at all").isFalse();
    }

    @Test
    void keypad_typesAQuantity_throughTheSpinnerEditorDocument() throws Exception {
        LineItem line = openFor(2);
        // openFor selectAll()'d the field, so the first key replaces the prefill.
        tapKeypad("5");
        assertThat(view.getSpinnerEditorForTest().getText()).isEqualTo("5");

        clickConfirm();
        PosEvent event = dispatcher.only(PosEventType.CHANGE_QTY_CONFIRM_PRESSED);
        assertThat(event.getProperty("lineItem", LineItem.class)).isSameAs(line);
        assertThat(event.getProperty("newQuantity", Integer.class)).isEqualTo(5);
    }

    @Test
    void keypad_digitFilterStillRejectsOverflowLengthLikeTyping() throws Exception {
        // The digit-only, length-capped filter governs a tapped key exactly as a keystroke: the
        // editor tops out at the digit count of the maximum (999 → 3 digits).
        openFor(2);
        SwingUtilities.invokeAndWait(() -> view.getSpinnerEditorForTest().setText(""));
        tapKeypad("9");
        tapKeypad("9");
        tapKeypad("9");
        tapKeypad("9"); // fourth digit rejected by the length cap
        assertThat(view.getSpinnerEditorForTest().getText()).isEqualTo("999");
    }

    @Test
    void keypad_backspaceAndClear_editThroughTheDocument() throws Exception {
        openFor(2);
        SwingUtilities.invokeAndWait(() -> view.getSpinnerEditorForTest().setText("12"));

        tapKeypad(OnScreenKeypad.BACKSPACE_LABEL);
        assertThat(view.getSpinnerEditorForTest().getText()).isEqualTo("1");

        tapKeypad(OnScreenKeypad.CLEAR_LABEL);
        assertThat(view.getSpinnerEditorForTest().getText()).isEmpty();
    }

    @Test
    void physicalKeyboardStillWorks_withTheKeypadPresent() throws Exception {
        openFor(2);
        SwingUtilities.invokeAndWait(() -> view.getSpinnerEditorForTest().setText(""));
        typeInto(view.getSpinnerEditorForTest(), "3");
        tapKeypad("4");
        assertThat(view.getSpinnerEditorForTest().getText()).isEqualTo("34");
    }

    @Test
    void everyKeypadKey_isNonFocusable() throws Exception {
        openFor(2);
        for (PosButton key : view.getKeypadForTest().getKeysForTest()) {
            assertThat(key.isFocusable())
                    .as("keypad key \"%s\" must not steal focus from the spinner editor", key.getText())
                    .isFalse();
        }
    }

    @Test
    void dialog_fitsWithinTerminalHeight_withSpinnerAndKeypad() throws Exception {
        openFor(2);
        int height = view.getHeight();
        System.out.println("[measurement] ChangeQuantityView packed height with keypad = " + height + "px");
        assertThat(height)
                .as("quantity dialog with spinner + keypad must fit within the 982px terminal")
                .isLessThanOrEqualTo(982);
    }

    // ---- helpers ---------------------------------------------------------

    private void tapKeypad(String label) throws Exception {
        SwingUtilities.invokeAndWait(() -> view.getKeypadForTest().getKeyForTest(label).doClick());
        // Drain any deferred formatter event (e.g. focus-driven select-all) before the next tap,
        // the way a real cashier's inter-tap delay does.
        SwingUtilities.invokeAndWait(() -> { });
    }

    private LineItem openFor(int quantity) throws Exception {
        LineItem line = new LineItem(WIDGET, quantity);
        SwingUtilities.invokeAndWait(() -> view.openFor(line));
        return line;
    }

    private void clickConfirm() throws Exception {
        SwingUtilities.invokeAndWait(() -> view.getConfirmButtonForTest().doClick());
    }

    private void clickCancel() throws Exception {
        SwingUtilities.invokeAndWait(() -> view.getCancelButtonForTest().doClick());
    }

    private static void typeInto(JTextField editor, String s) throws Exception {
        // Push each character through the Document, which routes via the installed
        // DocumentFilter — same path a real keystroke takes.
        SwingUtilities.invokeAndWait(() -> {
            try {
                editor.getDocument().insertString(
                        editor.getDocument().getLength(), s, null);
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        });
    }

    private static void pasteInto(JTextField editor, String s) throws Exception {
        // Use the system clipboard + editor.paste() so the paste really does exercise the
        // clipboard code path rather than a shortcut through setText. In some CI environments
        // the clipboard is unavailable — assume-out gracefully if so.
        AtomicReference<Boolean> pasted = new AtomicReference<>(false);
        SwingUtilities.invokeAndWait(() -> {
            try {
                Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                cb.setContents(new StringSelection(s), null);
                editor.selectAll();
                editor.paste();
                pasted.set(true);
            } catch (IllegalStateException ex) {
                // clipboard busy or unavailable
                pasted.set(false);
            }
        });
        assumeFalse(!pasted.get(), "system clipboard unavailable");
    }

    // ---- Dispatcher recorder --------------------------------------------

    static final class RecordingDispatcher implements IPosEventDispatcher {
        final List<PosEvent> events = new ArrayList<>();

        @Override
        public void dispatchPosEvent(PosEvent event) {
            events.add(event);
        }

        List<PosEvent> eventsOf(PosEventType type) {
            return events.stream().filter(e -> e.getType() == type).toList();
        }

        PosEvent only(PosEventType type) {
            List<PosEvent> matching = eventsOf(type);
            assertThat(matching).hasSize(1);
            return matching.get(0);
        }
    }
}
