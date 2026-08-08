package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Dialog-level tests for {@link CashModeChoiceView}. Locks in the two-button layout, the
 * "Next dollar disables (not hides) on whole-dollar totals", the dispatched-event payloads,
 * and the "title appears exactly once" invariant.
 */
class CashModeChoiceViewTest {

    private RecordingDispatcher dispatcher;
    private CashModeChoiceView view;

    @BeforeEach
    void setUp() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        dispatcher = new RecordingDispatcher();
        view = new CashModeChoiceView(null, dispatcher);
    }

    // ---- Cached amounts drive the mode-selected events ------------------

    @Test
    void openFor_storesBothAmounts_soModeSelectionCarriesTheRightPrefill() throws Exception {
        // The amounts are no longer painted on the button faces — they live in the header
        // and drive the prefillAmount property of the dispatched mode-selected event. The
        // event-payload tests below cover the wiring; this test just pins the storage.
        SwingUtilities.invokeAndWait(() ->
                view.openFor(new BigDecimal("7.30"), new BigDecimal("8.00")));

        assertThat(view.getExactAmountForTest()).isEqualByComparingTo("7.30");
        assertThat(view.getNextDollarAmountForTest()).isEqualByComparingTo("8.00");
    }

    // ---- Whole-dollar totals --------------------------------------------

    @Test
    void wholeDollarTotal_disablesNextDollarButton_ratherThanHidingIt() throws Exception {
        SwingUtilities.invokeAndWait(() ->
                view.openFor(new BigDecimal("7.00"), new BigDecimal("7.00")));

        // Disabled — not hidden — so the layout stays stable.
        assertThat(view.getNextDollarButtonForTest().isVisible()).isTrue();
        assertThat(view.getNextDollarButtonForTest().isEnabled()).isFalse();
        assertThat(view.getExactButtonForTest().isEnabled()).isTrue();
    }

    @Test
    void fractionalTotal_leavesBothButtonsEnabled() throws Exception {
        SwingUtilities.invokeAndWait(() ->
                view.openFor(new BigDecimal("7.30"), new BigDecimal("8.00")));

        assertThat(view.getExactButtonForTest().isEnabled()).isTrue();
        assertThat(view.getNextDollarButtonForTest().isEnabled()).isTrue();
    }

    // ---- Dispatched events ----------------------------------------------

    @Test
    void exactButtonClick_dispatchesModeSelected_withGrandTotalAsPrefill() throws Exception {
        SwingUtilities.invokeAndWait(() ->
                view.openFor(new BigDecimal("7.30"), new BigDecimal("8.00")));

        SwingUtilities.invokeAndWait(() -> view.getExactButtonForTest().doClick());

        List<PosEvent> events = dispatcher.eventsOf(PosEventType.CASH_EXACT_PRESSED);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getProperty("prefillAmount", BigDecimal.class))
                .isEqualByComparingTo("7.30");
    }

    @Test
    void nextDollarButtonClick_dispatchesModeSelected_withRoundedAmountAsPrefill()
            throws Exception {
        SwingUtilities.invokeAndWait(() ->
                view.openFor(new BigDecimal("7.30"), new BigDecimal("8.00")));

        SwingUtilities.invokeAndWait(() -> view.getNextDollarButtonForTest().doClick());

        List<PosEvent> events = dispatcher.eventsOf(PosEventType.CASH_NEXT_DOLLAR_PRESSED);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getProperty("prefillAmount", BigDecimal.class))
                .isEqualByComparingTo("8.00");
    }

    @Test
    void cancelClick_dispatchesCancel() throws Exception {
        SwingUtilities.invokeAndWait(() ->
                view.openFor(new BigDecimal("7.30"), new BigDecimal("8.00")));

        SwingUtilities.invokeAndWait(() -> view.getCancelButtonForTest().doClick());

        assertThat(dispatcher.eventsOf(PosEventType.CASH_CANCEL_PRESSED)).hasSize(1);
    }

    // ---- Header --------------------------------------------------------

    @Test
    void header_showsAmountDue_afterOpen() throws Exception {
        SwingUtilities.invokeAndWait(() ->
                view.openFor(new BigDecimal("7.30"), new BigDecimal("8.00")));

        assertThat(view.getHeaderAmountLabelForTest()).isNotNull();
        assertThat(view.getHeaderAmountLabelForTest().getText()).isEqualTo("$7.30");
    }

    @Test
    void nativeWindowTitle_isEmpty_soTitleAppearsExactlyOnceInTheHeaderStrip() {
        assertThat(view.getTitle()).isEmpty();
        assertThat(view.getHeaderTitleLabelForTest().getText()).isEqualTo("Cash Payment");
    }

    // ---- Recording dispatcher ------------------------------------------

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
