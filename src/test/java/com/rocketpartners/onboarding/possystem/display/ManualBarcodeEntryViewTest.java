package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Real-Swing tests for the manual barcode-entry keypad dialog. Skipped in headless CI — the
 * {@link javax.swing.JDialog} constructor requires a display.
 */
class ManualBarcodeEntryViewTest {

    private RecordingDispatcher dispatcher;
    private ManualBarcodeEntryView view;

    @BeforeEach
    void setUp() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        dispatcher = new RecordingDispatcher();
        view = new ManualBarcodeEntryView(null, dispatcher);
    }

    @Test
    void confirm_withDigits_dispatchesScanSubmitCarryingTheRawText() {
        view.getEntryFieldForTest().setText("012345678905");

        view.getConfirmButtonForTest().doClick();

        assertThat(dispatcher.received)
                .extracting(PosEvent::getType)
                .containsExactly(PosEventType.SCAN_SUBMIT_PRESSED);
        PosEvent submit = dispatcher.received.get(dispatcher.received.size() - 1);
        assertThat(submit.getProperty("raw", String.class)).isEqualTo("012345678905");
    }

    @Test
    void confirm_whenEmpty_dispatchesNothing_andShowsHint() {
        assertThat(view.getEntryFieldForTest().getText()).isEmpty();

        view.getConfirmButtonForTest().doClick();

        assertThat(dispatcher.received).isEmpty();
        assertThat(view.getHintLineForTest().getText()).isEqualTo("Enter a barcode.");
    }

    @Test
    void cancel_dispatchesNothing() {
        view.getEntryFieldForTest().setText("999");

        view.getCancelButtonForTest().doClick();

        assertThat(dispatcher.received).isEmpty();
    }

    @Test
    void entryField_acceptsDigits_rejectsNonDigits() {
        // A value containing a non-digit is rejected wholesale by the filter's replace; starting
        // from empty, the field stays empty.
        view.getEntryFieldForTest().setText("12ab3");
        assertThat(view.getEntryFieldForTest().getText()).isEmpty();

        // A pure-digit value is accepted verbatim.
        view.getEntryFieldForTest().setText("012345678905");
        assertThat(view.getEntryFieldForTest().getText()).isEqualTo("012345678905");
    }

    @Test
    void keypad_hasNoDecimalKey_becauseABarcodeIsWholeDigits() {
        assertThat(view.getKeypadForTest().hasDecimalKeyForTest()).isFalse();
    }

    @Test
    void title_isEnterBarcode() {
        assertThat(view.getHeaderTitleLabelForTest().getText()).isEqualTo("Enter Barcode");
    }

    // ---- Helpers -----------------------------------------------------------

    static final class RecordingDispatcher implements IPosEventDispatcher {
        final List<PosEvent> received = new ArrayList<>();

        @Override
        public void dispatchPosEvent(PosEvent event) {
            received.add(event);
        }
    }
}
