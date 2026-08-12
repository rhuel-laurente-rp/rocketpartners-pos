package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import org.junit.jupiter.api.AfterEach;
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
 * Dialog-level tests for {@link TenderConfirmView}: the contextual copy it renders, the two-outcome
 * event dispatch, and the ordinary (non-inverted) keyboard default that separates it from
 * {@link VoidBasketConfirmView}.
 */
class TenderConfirmViewTest {

    private RecordingDispatcher dispatcher;
    private TenderConfirmView view;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        dispatcher = new RecordingDispatcher();
        SwingUtilities.invokeAndWait(() -> {
            view = new TenderConfirmView(null, dispatcher,
                    PosEventType.CASH_TENDER_CONFIRM_PRESSED, PosEventType.CASH_TENDER_BACK_PRESSED,
                    "Confirm Payment", "Back");
            // PosDialog is modal; setVisible(true) inside openFor(...) would enter a nested
            // dispatch loop and stall the build. Force non-modal so openDialog() returns.
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

    // ---- Copy / context --------------------------------------------------

    @Test
    void openFor_rendersTitleDescriptionSummaryAndHeaderAmount() throws Exception {
        openFor("Confirm Payment", "Confirm the cash payment below.",
                "Exact Amount · $7.30", new BigDecimal("7.30"));

        assertThat(view.getHeaderTitleLabelForTest().getText()).isEqualTo("Confirm Payment");
        assertThat(view.getDescriptionLabelForTest().getText())
                .isEqualTo("Confirm the cash payment below.");
        assertThat(view.getSummaryLabelForTest().getText()).isEqualTo("Exact Amount · $7.30");
        assertThat(view.getHeaderAmountLabelForTest()).isNotNull();
        assertThat(view.getHeaderAmountLabelForTest().getText()).isEqualTo("$7.30");
    }

    @Test
    void title_appearsExactlyOnce() {
        // Native window title left empty so the header strip is the sole place the title lives.
        assertThat(view.getTitle()).as("native window title must be empty").isEmpty();
    }

    // ---- Footer consistency ----------------------------------------------

    @Test
    void confirmAndCancel_reportIdenticalSizes() {
        assertThat(view.getConfirmButtonForTest().getPreferredSize())
                .isEqualTo(view.getCancelButtonForTest().getPreferredSize());
    }

    // ---- Keyboard default ------------------------------------------------

    @Test
    void enterKey_triggersConfirm_notCancel() throws Exception {
        openFor("Confirm Payment", "Confirm the cash payment below.",
                "Exact Amount · $7.30", new BigDecimal("7.30"));

        // Ordinary commit dialog: PosDialog binds Enter (window-level) to the primary action, so a
        // stray Enter fires Confirm — the opposite of the deliberately-inverted VoidBasketConfirmView,
        // where Enter is routed to the safe secondary.
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JComponent root = (javax.swing.JComponent) view.getContentPane();
            root.getActionMap().get("primary").actionPerformed(
                    new java.awt.event.ActionEvent(view, 0, "primary"));
        });

        assertThat(dispatcher.eventsOf(PosEventType.CASH_TENDER_CONFIRM_PRESSED)).hasSize(1);
        assertThat(dispatcher.eventsOf(PosEventType.CASH_TENDER_BACK_PRESSED)).isEmpty();
    }

    // ---- Outcomes --------------------------------------------------------

    @Test
    void confirm_dispatchesConfirmEvent() throws Exception {
        openFor("Confirm Payment", "d", "s", new BigDecimal("5.00"));

        SwingUtilities.invokeAndWait(() -> view.getConfirmButtonForTest().doClick());

        assertThat(dispatcher.eventsOf(PosEventType.CASH_TENDER_CONFIRM_PRESSED)).hasSize(1);
        assertThat(dispatcher.eventsOf(PosEventType.CASH_TENDER_BACK_PRESSED)).isEmpty();
    }

    @Test
    void cancel_dispatchesCancelEvent() throws Exception {
        openFor("Confirm Payment", "d", "s", new BigDecimal("5.00"));

        SwingUtilities.invokeAndWait(() -> view.getCancelButtonForTest().doClick());

        assertThat(dispatcher.eventsOf(PosEventType.CASH_TENDER_BACK_PRESSED)).hasSize(1);
        assertThat(dispatcher.eventsOf(PosEventType.CASH_TENDER_CONFIRM_PRESSED)).isEmpty();
    }

    @Test
    void escapeKey_dispatchesCancelEvent() throws Exception {
        openFor("Confirm Payment", "d", "s", new BigDecimal("5.00"));

        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JComponent root = (javax.swing.JComponent) view.getContentPane();
            root.getActionMap().get("cancel").actionPerformed(
                    new java.awt.event.ActionEvent(view, 0, "cancel"));
        });

        assertThat(dispatcher.eventsOf(PosEventType.CASH_TENDER_BACK_PRESSED)).hasSize(1);
        assertThat(dispatcher.eventsOf(PosEventType.CASH_TENDER_CONFIRM_PRESSED)).isEmpty();
    }

    // ---- helpers ---------------------------------------------------------

    private void openFor(String title, String description, String summary, BigDecimal amount)
            throws Exception {
        SwingUtilities.invokeAndWait(() -> view.openFor(title, description, summary, amount));
    }

    /** Recording dispatcher — same pattern as {@link VoidBasketConfirmViewTest}. */
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
