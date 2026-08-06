package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.commons.model.TenderType;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.commons.model.TransactionState;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.repository.inmemory.InMemoryItemRepository;
import com.rocketpartners.onboarding.possystem.service.TaxService;
import com.rocketpartners.onboarding.possystem.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ReceiptViewControllerTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("10.00"));
    private static final Item GADGET = new Item("UPC-G", "Gadget", new BigDecimal("2.50"));

    private PosComponent pos;
    private ReceiptView view;
    private ReceiptViewController controller;
    private RecordingListener notifications;

    @BeforeEach
    void setUp() {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);
        items.put(GADGET.getUpc(), GADGET);
        pos = new PosComponent(
                new InMemoryItemRepository(items),
                new TaxService(BigDecimal.ZERO),
                "Test Store",
                7,
                false);
        view = mock(ReceiptView.class);
        controller = new ReceiptViewController(view, "Test Store", 7);
        notifications = new RecordingListener(EnumSet.allOf(PosEventType.class));
        pos.register(notifications);
        pos.addController(controller);
        pos.start();
    }

    private Transaction ringUpAndPayCash(BigDecimal cash, Item... items) {
        TransactionService svc = pos.getTransactionService();
        svc.startTransaction();
        for (Item item : items) svc.addItemByUpc(item.getUpc(), 1);
        svc.total();
        return svc.tenderCash(cash);
    }

    @Test
    void transactionCompleted_opensDialog_withServiceGeneratedText() {
        Transaction paid = ringUpAndPayCash(new BigDecimal("15.00"), WIDGET);

        pos.dispatchPosEvent(completed(paid));

        String expected = pos.getTransactionService()
                .generateReceipt(paid, "Test Store", 7);
        verify(view).setReceiptText(expected);
        verify(view).openDialog();
    }

    @Test
    void transactionCompleted_passesReceiptTextThroughUnmodified() {
        Transaction paid = ringUpAndPayCash(new BigDecimal("15.00"), WIDGET);

        pos.dispatchPosEvent(completed(paid));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(view).setReceiptText(captor.capture());
        // The exact string produced by the service — verify equality, not just that it
        // contains substrings.
        assertThat(captor.getValue()).isEqualTo(
                pos.getTransactionService().generateReceipt(paid, "Test Store", 7));
    }

    @Test
    void voidedLines_areAbsentFromReceipt_butStillPresentOnTransaction() {
        TransactionService svc = pos.getTransactionService();
        svc.startTransaction();
        LineItem widgetLine = svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.addItemByUpc(GADGET.getUpc(), 1);
        svc.voidLine(widgetLine);
        svc.total();
        Transaction paid = svc.tenderCash(new BigDecimal("2.50"));

        pos.dispatchPosEvent(completed(paid));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(view).setReceiptText(captor.capture());
        String receipt = captor.getValue();
        assertThat(receipt).doesNotContain("Widget");
        assertThat(receipt).contains("Gadget");

        // ... but the line is still on the transaction (soft-delete for audit trail).
        assertThat(paid.getLineItems()).hasSize(2);
        assertThat(paid.getLineItems().get(0).getItem()).isEqualTo(WIDGET);
        assertThat(paid.getLineItems().get(0).isVoided()).isTrue();
    }

    @Test
    void dismissPressed_closesDialog_andDispatchesReceiptDismissed() {
        Transaction paid = ringUpAndPayCash(new BigDecimal("15.00"), WIDGET);
        pos.dispatchPosEvent(completed(paid));

        pos.dispatchPosEvent(new PosEvent(PosEventType.RECEIPT_DISMISS_PRESSED));

        verify(view).closeDialog();
        assertThat(notifications.countOf(PosEventType.RECEIPT_DISMISSED)).isEqualTo(1);
    }

    @Test
    void dismissEndsSale_customerViewControllerResetsAndOpensFreshTransaction() {
        // Combine ReceiptViewController with a CustomerViewController so this test observes the
        // full "dismiss ends sale" contract, not just the event dispatch. The customer
        // controller's onStart opens a fresh transaction; ring up and pay on THAT one.
        CustomerView customerView = mock(CustomerView.class);
        CustomerViewController customerController = new CustomerViewController(customerView);
        pos.addController(customerController);

        TransactionService svc = pos.getTransactionService();
        Transaction opened = svc.getCurrentTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        Transaction paid = svc.tenderCash(new BigDecimal("15.00"));
        assertThat(paid).isSameAs(opened);
        pos.dispatchPosEvent(completed(paid));
        org.mockito.Mockito.reset(customerView);

        pos.dispatchPosEvent(new PosEvent(PosEventType.RECEIPT_DISMISS_PRESSED));

        Transaction next = pos.getTransactionService().getCurrentTransaction();
        assertThat(next).isNotNull();
        assertThat(next).isNotSameAs(paid);
        assertThat(next.getState()).isEqualTo(TransactionState.IN_PROGRESS);
        verify(customerView).updateBasket(List.of(), BigDecimal.ZERO);
        verify(customerView).setBasketInputEnabled(true);
        verify(customerView).setTenderInputEnabled(false);
    }

    @Test
    void transactionCompleted_withoutTransactionProperty_isNoOp() {
        pos.dispatchPosEvent(new PosEvent(PosEventType.TRANSACTION_COMPLETED));

        verify(view, never()).setReceiptText(any());
        verify(view, never()).openDialog();
    }

    // ---- Helpers -----------------------------------------------------------

    private static PosEvent completed(Transaction tx) {
        Map<String, Object> props = new HashMap<>();
        props.put("transaction", tx);
        props.put("tenderType", TenderType.CASH);
        props.put("amountTendered", new BigDecimal("15.00"));
        props.put("changeDue", new BigDecimal("0.00"));
        return new PosEvent(PosEventType.TRANSACTION_COMPLETED, props);
    }

    static final class RecordingListener implements IPosEventListener {
        final Set<PosEventType> types;
        final List<PosEvent> received = new ArrayList<>();

        RecordingListener(Set<PosEventType> types) {
            this.types = types;
        }

        @Override
        public Set<PosEventType> getListeningEventTypes() {
            return types;
        }

        @Override
        public void onPosEvent(PosEvent event) {
            received.add(event);
        }

        int countOf(PosEventType type) {
            return (int) received.stream().filter(e -> e.getType() == type).count();
        }
    }
}
