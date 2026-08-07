package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Header journal-status indicator: defaults to OFFLINE, flips to LIVE and back as
 * {@link CustomerView#setJournalConnected(boolean)} is invoked. Skipped headless — the
 * indicator lives on a real {@link javax.swing.JFrame} header.
 */
class CustomerViewJournalStatusTest {

    @Test
    void indicator_startsOffline_andCanBeToggled() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            assertThat(view.isJournalConnectedForTest()).isFalse();

            view.setJournalConnected(true);
            waitForEdt();
            assertThat(view.isJournalConnectedForTest()).isTrue();

            view.setJournalConnected(false);
            waitForEdt();
            assertThat(view.isJournalConnectedForTest()).isFalse();
        } finally {
            view.dispose();
        }
    }

    @Test
    void setJournalConnected_offEdt_marshalsToEdt() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CustomerView view = new CustomerView("test", List.of(), noop());
        try {
            AtomicBoolean invokedOnEdt = new AtomicBoolean();
            Thread t = new Thread(() -> {
                view.setJournalConnected(true);
                invokedOnEdt.set(!SwingUtilities.isEventDispatchThread());
            }, "off-edt");
            t.start();
            t.join(2_000);
            waitForEdt();
            assertThat(view.isJournalConnectedForTest()).isTrue();
            assertThat(invokedOnEdt.get())
                    .as("caller should have been off the EDT for the marshaling path to matter")
                    .isTrue();
        } finally {
            view.dispose();
        }
    }

    private static void waitForEdt() throws Exception {
        // A no-op invokeAndWait flushes the queue.
        SwingUtilities.invokeAndWait(() -> {});
    }

    private static IPosEventDispatcher noop() {
        return event -> {};
    }
}
