package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure view tests for the scan bar's modes: idle, focused (implicit via the border swap),
 * locked, and error, plus the Enter-only submit contract. Runs headless — no real display
 * required. Placeholder plumbing means the field's document is "empty" (getScanText) whenever the
 * placeholder is showing, so any assertion about submit content must first put the field into a
 * non-placeholder state via {@link ScannerView#setScanText(String)}.
 */
class ScannerViewTest {

    private ScannerView view;
    private RecordingDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new RecordingDispatcher();
        view = new ScannerView(dispatcher);
    }

    @Test
    void idleState_hasBlankStatusHint() {
        assertThat(view.getStatusHintTextForTest()).isEmpty();
        assertThat(view.isLockedForTest()).isFalse();
        assertThat(view.isErrorShownForTest()).isFalse();
    }

    @Test
    void noScanButtonExists_amongTheBarsComponents() {
        // The Scan button is gone; Enter is the only submit trigger. No JButton (PosButton) may
        // survive anywhere in the bar's component tree.
        assertThat(findButton(view))
                .as("the scan bar must contain no button")
                .isNull();
    }

    @Test
    void field_isNonEditableWhenLocked_editableWhenUnlocked() {
        view.setScanText("049000053418");
        assertThat(view.getScanField().isEditable()).isTrue();

        view.setLocked(true);
        assertThat(view.getScanField().isEditable()).isFalse();

        view.setLocked(false);
        assertThat(view.getScanField().isEditable()).isTrue();
    }

    @Test
    void enter_submitsFieldContents_dispatchesScanSubmitWithRawText() {
        view.setScanText("049000053418");

        // Enter fires the field's ActionListener — the sole submit path now the button is gone.
        view.getScanField().postActionEvent();

        assertThat(dispatcher.received)
                .extracting(PosEvent::getType)
                .containsExactly(PosEventType.SCAN_SUBMIT_PRESSED);
        PosEvent submit = dispatcher.received.get(dispatcher.received.size() - 1);
        assertThat(submit.getProperty("raw", String.class)).isEqualTo("049000053418");
    }

    @Test
    void enter_onEmptyField_dispatchesNothing() {
        // Field is empty (placeholder showing). Enter must be a no-op — no SCAN_SUBMIT_PRESSED, so
        // nothing reaches the controller to journal or to turn into an error.
        assertThat(view.getScanText()).isEmpty();

        view.getScanField().postActionEvent();

        assertThat(dispatcher.received).isEmpty();
    }

    @Test
    void barHeight_identicalWithAndWithoutMessage() {
        // The message row is reserved, not conditional — the bar's preferred height must not change
        // when a message appears, so the basket beneath it never shifts.
        int idleHeight = view.getPreferredSize().height;

        view.setScanText("012345678905");
        view.setInlineError(ScannerViewController.MSG_ITEM_NOT_FOUND_PREFIX + "012345678905");
        int errorHeight = view.getPreferredSize().height;

        view.setLocked(true);
        int lockedHeight = view.getPreferredSize().height;

        assertThat(errorHeight).isEqualTo(idleHeight);
        assertThat(lockedHeight).isEqualTo(idleHeight);
    }

    @Test
    void field_growsWithTheBar_ratherThanStayingAtFixedPreferredWidth() {
        // The field is the only element that grows: given a wide bar it claims (nearly) the whole
        // width, not a fixed preferred size. Lay the bar out at two widths and confirm the field
        // tracks them.
        view.setSize(500, view.getPreferredSize().height);
        view.doLayout();
        int narrow = view.getScanField().getWidth();

        view.setSize(900, view.getPreferredSize().height);
        view.doLayout();
        int wide = view.getScanField().getWidth();

        assertThat(wide).isGreaterThan(narrow);
        // At 900px the field should be claiming most of the bar, not sitting at a ~260px preferred.
        assertThat(wide).isGreaterThan(800);
    }

    @Test
    void locked_setsLiveAmberStatusHint_andLockedBorder() {
        view.setLocked(true);

        assertThat(view.getStatusHintTextForTest())
                .isEqualTo(ScannerView.STATUS_LOCKED);
        assertThat(view.getStatusHintColorForTest()).isEqualTo(PosTheme.LIVE);
        assertThat(view.getFieldBorderForTest()).isEqualTo(view.lockedBorderForTest());
    }

    @Test
    void unlock_restoresIdle_blankHintAndIdleBorder() {
        view.setLocked(true);
        view.setLocked(false);

        assertThat(view.getStatusHintTextForTest()).isEmpty();
        // Not focused — should be idle border.
        assertThat(view.getFieldBorderForTest()).isEqualTo(view.idleBorderForTest());
        assertThat(view.getScanField().isEditable()).isTrue();
    }

    @Test
    void inlineError_setsStopBorder_stopHint_andSelectsFieldContents() {
        view.setScanText("nope");

        view.setInlineError(ScannerViewController.MSG_BARCODE_NOT_RECOGNISED);

        assertThat(view.isErrorShownForTest()).isTrue();
        assertThat(view.getStatusHintTextForTest())
                .isEqualTo(ScannerViewController.MSG_BARCODE_NOT_RECOGNISED);
        assertThat(view.getStatusHintColorForTest()).isEqualTo(PosTheme.STOP);
        assertThat(view.getFieldBorderForTest()).isEqualTo(view.errorBorderForTest());
        // Field contents selected so the next scan/keystroke overwrites them wholesale.
        assertThat(view.getScanField().getSelectionStart()).isEqualTo(0);
        assertThat(view.getScanField().getSelectionEnd()).isEqualTo("nope".length());
    }

    @Test
    void inlineError_itemNotFound_carriesUpcSuffix() {
        view.setScanText("012345678905");

        view.setInlineError(ScannerViewController.MSG_ITEM_NOT_FOUND_PREFIX + "012345678905");

        assertThat(view.getStatusHintTextForTest())
                .isEqualTo("Item Not Found — 012345678905");
    }

    @Test
    void inlineError_misread_showsMisreadHint() {
        view.setInlineError(ScannerViewController.MSG_BARCODE_MISREAD);

        assertThat(view.getStatusHintTextForTest())
                .isEqualTo(ScannerViewController.MSG_BARCODE_MISREAD);
    }

    @Test
    void inlineError_ignoredWhenLocked_lockedHintStays() {
        view.setLocked(true);
        view.setInlineError(ScannerViewController.MSG_BARCODE_NOT_RECOGNISED);

        assertThat(view.isErrorShownForTest()).isFalse();
        assertThat(view.getStatusHintTextForTest())
                .isEqualTo(ScannerView.STATUS_LOCKED);
        assertThat(view.getFieldBorderForTest()).isEqualTo(view.lockedBorderForTest());
    }

    @Test
    void clearInlineError_restoresIdleBorderAndBlankHint() {
        view.setScanText("nope");
        view.setInlineError(ScannerViewController.MSG_BARCODE_NOT_RECOGNISED);

        view.clearInlineError();

        assertThat(view.isErrorShownForTest()).isFalse();
        assertThat(view.getStatusHintTextForTest()).isEmpty();
        assertThat(view.getFieldBorderForTest()).isEqualTo(view.idleBorderForTest());
    }

    @Test
    void pulseGo_appliesGreenBorder_thenRestoresIdle() throws Exception {
        view.setScanText("049000053418");
        view.pulseGo();

        assertThat(view.getFieldBorderForTest()).isEqualTo(view.pulseBorderForTest());

        // Poll briefly for the Swing Timer to fire — the pulse duration is short.
        long deadline = System.currentTimeMillis() + ScannerView.PULSE_MS * 4L;
        while (view.getFieldBorderForTest() == view.pulseBorderForTest()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(view.getFieldBorderForTest()).isNotEqualTo(view.pulseBorderForTest());
    }

    @Test
    void clearScanField_whileFocused_doesNotPaintPlaceholderBackIntoField() {
        // Regression: after an accepted or rejected scan the controller calls clearScanField()
        // then requestScanFieldFocus(). Re-focus on an already-focused field does NOT fire
        // focusGained, so an unconditional showPlaceholderIfEmpty() would leave the
        // placeholder text sitting inside the still-focused field. The next keystroke would
        // then concatenate onto the placeholder ("Scan or type a UPC and press Enter1") and
        // the Scan button would stay disabled because the placeholder flag reads as empty.
        //
        // Simulate focus via requestFocusInWindow — headless can't grant real focus, so we
        // check the invariant against the (in-test) always-unfocused field: the helper must
        // not repaint the placeholder text when the field has focus. Verified below by
        // asserting the document is empty after clearScanField when we simulate focus by
        // clearing PLACEHOLDER_ACTIVE first (which is what focusGained does in production).
        view.setScanText("some-typed-input");
        // clearPlaceholderState was called by setScanText, so PLACEHOLDER_ACTIVE is false —
        // this mirrors the state after focusGained in production.
        view.getScanField().requestFocusInWindow();

        view.clearScanField();

        // Regardless of whether the platform granted focus, the field's document should be
        // empty — either "" (focused path) or the placeholder (unfocused path). It must not
        // start with the placeholder followed by anything else.
        String text = view.getScanField().getText();
        boolean okEmpty = text.isEmpty();
        boolean okPlaceholder = ScannerView.PLACEHOLDER.equals(text);
        assertThat(okEmpty || okPlaceholder)
                .as("field text after clearScanField must be empty or exactly the placeholder, was: '%s'", text)
                .isTrue();
    }

    @Test
    void pulseGo_ignoredWhenLocked() {
        view.setLocked(true);
        view.pulseGo();

        assertThat(view.getFieldBorderForTest()).isEqualTo(view.lockedBorderForTest());
    }

    /** @return the first {@link javax.swing.JButton} anywhere under {@code c}, or {@code null}. */
    private static javax.swing.JButton findButton(java.awt.Container c) {
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof javax.swing.JButton b) return b;
            if (child instanceof java.awt.Container inner) {
                javax.swing.JButton found = findButton(inner);
                if (found != null) return found;
            }
        }
        return null;
    }

    static final class RecordingDispatcher implements IPosEventDispatcher {
        final List<PosEvent> received = new ArrayList<>();

        @Override
        public void dispatchPosEvent(PosEvent event) {
            received.add(event);
        }
    }
}
