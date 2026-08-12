package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Controller-level tests. The view is mocked (Mockito instantiates the {@link javax.swing.JDialog}
 * subclass without calling its constructor, so no display is required), keeping these headless.
 */
class ManualBarcodeEntryViewControllerTest {

    @Test
    void manualEntryPressed_opensTheDialog() {
        ManualBarcodeEntryView view = mock(ManualBarcodeEntryView.class);
        ManualBarcodeEntryViewController controller = new ManualBarcodeEntryViewController(view);

        controller.onPosEvent(new PosEvent(PosEventType.MANUAL_ENTRY_PRESSED));

        verify(view).prepareAndOpen();
    }

    @Test
    void unrelatedEvent_doesNotOpenTheDialog() {
        ManualBarcodeEntryView view = mock(ManualBarcodeEntryView.class);
        ManualBarcodeEntryViewController controller = new ManualBarcodeEntryViewController(view);

        controller.onPosEvent(new PosEvent(PosEventType.ITEM_ADDED));

        verify(view, never()).prepareAndOpen();
    }

    @Test
    void listensOnlyForManualEntryPressed() {
        ManualBarcodeEntryView view = mock(ManualBarcodeEntryView.class);
        ManualBarcodeEntryViewController controller = new ManualBarcodeEntryViewController(view);

        assertThat(controller.getListeningEventTypes())
                .containsExactly(PosEventType.MANUAL_ENTRY_PRESSED);
    }

    @Test
    void onEnd_closesTheDialog() {
        ManualBarcodeEntryView view = mock(ManualBarcodeEntryView.class);
        ManualBarcodeEntryViewController controller = new ManualBarcodeEntryViewController(view);

        controller.onEnd();

        verify(view).closeDialog();
    }

    @Test
    void nullView_isRejected() {
        try {
            new ManualBarcodeEntryViewController(null);
            assertThat(false).as("expected IllegalArgumentException").isTrue();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
