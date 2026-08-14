package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.dto.TransactionDto;
import com.rocketpartners.onboarding.commons.model.Discount;
import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.commons.model.TransactionState;
import com.rocketpartners.onboarding.possystem.component.CloudApiComponent;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.display.CustomerViewControllerTest.RecordingListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.repository.inmemory.InMemoryItemRepository;
import com.rocketpartners.onboarding.possystem.service.DiscountSession;
import com.rocketpartners.onboarding.possystem.service.TaxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The Total-time discount flow: the engine call happens off the EDT, its result is applied on the
 * EDT, and both the success and failure paths converge on tender being enabled — the failure path
 * additionally surfaces a visible {@code DISCOUNTS_UNAVAILABLE} error while completing the sale.
 */
class CustomerViewControllerDiscountTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("10.00"));

    private PosComponent pos;
    private CustomerView view;
    private DiscountSession session;
    private RecordingListener notifications;

    @BeforeEach
    void setUp() {
        Map<String, Item> items = new HashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);
        pos = new PosComponent(new InMemoryItemRepository(items),
                new TaxService(new BigDecimal("0.07")), "Test", 1, false);
        view = mock(CustomerView.class);
        session = new DiscountSession();
        notifications = new RecordingListener(EnumSet.allOf(PosEventType.class));
        pos.register(notifications);
    }

    /** Runs work and delivers the result inline — lets the flow be asserted synchronously. */
    private static CustomerViewController.DiscountCalcScheduler synchronous() {
        return (work, onResult) -> onResult.accept(work.get());
    }

    /** A CloudApiComponent whose calculate() returns a canned result and records its thread. */
    private static final class StubApi extends CloudApiComponent {
        private final CalculateResult result;
        volatile Boolean calledOnEdt;
        PromoRule promoRule; // returned by promoRuleByCode when the code matches

        StubApi(CalculateResult result) {
            super("http://localhost:1");
            this.result = result;
        }

        @Override
        public CalculateResult calculate(TransactionDto request) {
            calledOnEdt = SwingUtilities.isEventDispatchThread();
            return result;
        }

        @Override
        public java.util.Optional<PromoRule> promoRuleByCode(String code) {
            return promoRule != null && promoRule.code().equals(code)
                    ? java.util.Optional.of(promoRule) : java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<PromoRule> promoRuleForUpc(String upc) {
            return promoRule != null && promoRule.targetUpc().equals(upc)
                    ? java.util.Optional.of(promoRule) : java.util.Optional.empty();
        }
    }

    private void addWidgetAndController(StubApi api,
                                        CustomerViewController.DiscountCalcScheduler scheduler) {
        CustomerViewController controller = new CustomerViewController(view, api, session, scheduler);
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc())); // subtotal 10.00
    }

    @Test
    void total_success_appliesDiscountsInOrder_taxesPostDiscount_enablesTender() {
        Discount d = new Discount("SENIOR_20", "Senior 20%", DiscountType.PERCENT_OFF,
                new BigDecimal("20"), new BigDecimal("2.00"));
        StubApi api = new StubApi(CloudApiComponent.CalculateResult.ok(List.of(d)));
        addWidgetAndController(api, synchronous());

        pos.dispatchPosEvent(new PosEvent(PosEventType.TOTAL_PRESSED));

        Transaction tx = pos.getTransactionService().getCurrentTransaction();
        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(tx.getDiscounts()).extracting(Discount::getDiscountId).containsExactly("SENIOR_20");
        assertThat(tx.discountTotal()).isEqualByComparingTo("2.00");
        // Tax on (10.00 - 2.00) = 8.00 * 0.07 = 0.56; grand total 8.56.
        assertThat(tx.taxTotal()).isEqualByComparingTo("0.56");
        assertThat(tx.grandTotal()).isEqualByComparingTo("8.56");

        assertThat(notifications.countOf(PosEventType.DISCOUNT_REQUEST_SENT)).isEqualTo(1);
        assertThat(notifications.countOf(PosEventType.DISCOUNT_APPLIED)).isEqualTo(1);
        verify(view).setTenderInputEnabled(true);
    }

    @Test
    void total_engineDown_completesSaleWithNoDiscounts_dispatchesError_enablesTender() {
        StubApi api = new StubApi(CloudApiComponent.CalculateResult.fail("connection refused"));
        addWidgetAndController(api, synchronous());

        pos.dispatchPosEvent(new PosEvent(PosEventType.TOTAL_PRESSED));

        Transaction tx = pos.getTransactionService().getCurrentTransaction();
        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(tx.getDiscounts()).isEmpty();
        assertThat(notifications.countOf(PosEventType.DISCOUNT_APPLIED)).isZero();
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("DISCOUNTS_UNAVAILABLE");
        // The stranded-in-TOTALED failure is the one to guard against: tender must still enable.
        verify(view).setTenderInputEnabled(true);
    }

    @Test
    void httpCall_runsOffTheEventDispatchThread() throws Exception {
        Discount d = new Discount("X", "X", DiscountType.PERCENT_OFF,
                new BigDecimal("10"), new BigDecimal("1.00"));
        StubApi api = new StubApi(CloudApiComponent.CalculateResult.ok(List.of(d)));
        // Real (default) scheduler: work on a daemon thread, callback marshalled to the EDT.
        CustomerViewController controller = new CustomerViewController(view, api, session);
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));

        // Press Total ON the EDT, so "not on the EDT" inside calculate is a meaningful assertion.
        SwingUtilities.invokeAndWait(() ->
                pos.dispatchPosEvent(new PosEvent(PosEventType.TOTAL_PRESSED)));

        await().atMost(2, TimeUnit.SECONDS).until(() -> api.calledOnEdt != null);
        assertThat(api.calledOnEdt)
                .as("the discount engine call must not run on the EDT — a 2s timeout would freeze the UI")
                .isFalse();
    }

    @Test
    void promoDiscount_addsAnInertFreeRowBeneathTheProduct() {
        // Buy-1-Get-1 on the widget; two widgets in the basket -> one free unit at $10.00 off.
        Discount promo = new Discount("BOGO", "Buy 1 Get 1", DiscountType.PROMO,
                BigDecimal.ZERO, new BigDecimal("10.00"));
        StubApi api = new StubApi(CloudApiComponent.CalculateResult.ok(List.of(promo)));
        api.promoRule = new CloudApiComponent.PromoRule("BOGO", "Buy 1 Get 1", WIDGET.getUpc(),
                DiscountType.PROMO, null, 1, 1);
        CustomerViewController controller = new CustomerViewController(view, api, session, synchronous());
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc())); // qty 2

        pos.dispatchPosEvent(new PosEvent(PosEventType.TOTAL_PRESSED));

        // The display list handed to the view contains a FreeLineItem for the freed unit; the
        // domain transaction still holds only the one real (qty-2) product line.
        org.mockito.Mockito.verify(view, org.mockito.Mockito.atLeastOnce()).updateBasket(
                org.mockito.ArgumentMatchers.argThat(rows ->
                        rows.stream().anyMatch(r -> r instanceof FreeLineItem)),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        Transaction tx = pos.getTransactionService().getCurrentTransaction();
        assertThat(tx.getLineItems()).hasSize(1);
        assertThat(tx.discountTotal()).isEqualByComparingTo("10.00");
    }

    @Test
    void perUpcDiscount_previewsAsADiscountRowOnAdd_andUpdatesTheSummary() {
        // A 25%-off rule on the widget's UPC. Adding one widget ($10.00) should preview a discount
        // row in the basket and a $2.50 discount in the summary — before Total, no engine call.
        StubApi api = new StubApi(CloudApiComponent.CalculateResult.ok(List.of()));
        api.promoRule = new CloudApiComponent.PromoRule("REIGN_25", "25% Off Reign", WIDGET.getUpc(),
                DiscountType.PERCENT_OFF, new BigDecimal("25"), null, null);
        CustomerViewController controller = new CustomerViewController(view, api, session, synchronous());
        pos.addController(controller);
        pos.start();

        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc())); // qty 1 @ 10.00 -> 25% = 2.50 off

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<com.rocketpartners.onboarding.commons.model.LineItem>> rows =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        org.mockito.ArgumentCaptor<BigDecimal> subtotal = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
        org.mockito.ArgumentCaptor<BigDecimal> discount = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
        verify(view, org.mockito.Mockito.atLeastOnce()).updateBasket(
                rows.capture(), subtotal.capture(), discount.capture(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        assertThat(rows.getValue()).anyMatch(r -> r instanceof DiscountLineItem);
        assertThat(subtotal.getValue()).isEqualByComparingTo("10.00");
        assertThat(discount.getValue()).isEqualByComparingTo("2.50");
    }

    @Test
    void resumeEditing_reopensTotaledOrder_clearsDiscounts_andLetsMoreItemsBeAdded() {
        Discount d = new Discount("SENIOR_20", "Senior 20%", DiscountType.PERCENT_OFF,
                new BigDecimal("20"), new BigDecimal("2.00"));
        StubApi api = new StubApi(CloudApiComponent.CalculateResult.ok(List.of(d)));
        addWidgetAndController(api, synchronous());
        pos.dispatchPosEvent(new PosEvent(PosEventType.TOTAL_PRESSED));

        Transaction tx = pos.getTransactionService().getCurrentTransaction();
        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(tx.getDiscounts()).isNotEmpty();

        // Press the header "Add Item" (resume) control.
        pos.dispatchPosEvent(new PosEvent(PosEventType.RESUME_EDITING_PRESSED));

        assertThat(tx.getState()).isEqualTo(TransactionState.IN_PROGRESS);
        assertThat(tx.getDiscounts()).isEmpty();
        assertThat(notifications.countOf(PosEventType.TRANSACTION_RESUMED)).isEqualTo(1);

        // Editable again: a further scan rings up on the same transaction (qty 1 -> 2).
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        assertThat(tx.getLineItems().get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void withoutEngine_totalEnablesTenderImmediately_asBefore() {
        // The engine-less (Phase-1) constructor path must still work: tender on, no discount call.
        CustomerViewController controller = new CustomerViewController(view);
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));

        pos.dispatchPosEvent(new PosEvent(PosEventType.TOTAL_PRESSED));

        assertThat(notifications.countOf(PosEventType.DISCOUNT_REQUEST_SENT)).isZero();
        verify(view).setTenderInputEnabled(true);
    }

    private static PosEvent quickAdd(String upc) {
        Map<String, Object> props = new HashMap<>();
        props.put("upc", upc);
        return new PosEvent(PosEventType.QUICK_ADD_PRESSED, props);
    }
}
