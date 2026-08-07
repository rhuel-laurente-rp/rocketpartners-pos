package com.rocketpartners.onboarding.possystem.component;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.commons.model.TenderType;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.repository.inmemory.InMemoryItemRepository;
import com.rocketpartners.onboarding.possystem.service.TaxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.SwingUtilities;

import static org.assertj.core.api.Assertions.assertThat;

class JournalListenerTest {

    private static final Item WIDGET = new Item("012345678905", "Widget", new BigDecimal("2.50"));

    private PosComponent pos;
    private CapturingJournal journal;

    @BeforeEach
    void setUp() {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);
        pos = new PosComponent(
                new InMemoryItemRepository(items),
                new TaxService(BigDecimal.ZERO),
                "STORE-01", 1, false);
        journal = new CapturingJournal();
        pos.addController(new JournalListener(journal));
        pos.start();
    }

    @Test
    void posStarted_isJournaledOnStart() {
        JournalRecord first = journal.records.get(0);
        assertThat(first.getEvent()).isEqualTo("POS_STARTED");
        assertThat(first.getStore()).isEqualTo("STORE-01");
        assertThat(first.getLane()).isEqualTo(1);
    }

    @Test
    void itemAddedEvent_isJournaled_withUpcQtyDescExt() {
        pos.getTransactionService().startTransaction();
        LineItem li = pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), 2);
        Map<String, Object> props = new HashMap<>();
        props.put("lineItem", li);
        pos.dispatchPosEvent(new PosEvent(PosEventType.ITEM_ADDED, props));

        JournalRecord r = journal.lastOf("ITEM_ADDED");
        assertThat(r.getFields()).containsEntry("upc", "012345678905");
        assertThat(r.getFields()).containsEntry("qty", 2);
        assertThat(r.getFields()).containsEntry("desc", "Widget");
        assertThat(r.getFields()).containsEntry("ext", "5.00");
    }

    @Test
    void cashTenderedEvent_journalsTenderTypeAndAmountsNotInstrumentDetails() {
        Map<String, Object> props = new HashMap<>();
        props.put("tenderType", TenderType.CASH);
        props.put("amountTendered", new BigDecimal("20.00"));
        props.put("changeDue", new BigDecimal("2.50"));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_TENDERED, props));

        JournalRecord r = journal.lastOf("CASH_TENDERED");
        assertThat(r.getFields()).containsEntry("tender", "CASH");
        assertThat(r.getFields()).containsEntry("amount", "20.00");
        assertThat(r.getFields()).containsEntry("change", "2.50");
    }

    @Test
    void cardTenderedEvent_doesNotContainPaymentInstrumentPropertiesEvenIfSupplied() {
        Map<String, Object> props = new HashMap<>();
        props.put("tenderType", TenderType.CREDIT);
        props.put("amountTendered", new BigDecimal("100.00"));
        props.put("changeDue", BigDecimal.ZERO);
        props.put("cardNumber", "4111-1111-1111-1111"); // never journaled
        pos.dispatchPosEvent(new PosEvent(PosEventType.CARD_TENDERED, props));

        JournalRecord r = journal.lastOf("CARD_TENDERED");
        assertThat(r.getFields()).containsEntry("tender", "CREDIT")
                .containsEntry("amount", "100.00");
        assertThat(r.getFields()).doesNotContainKey("cardNumber");
        // And it can't leak via any other channel:
        assertThat(r.toPipeDelimited()).doesNotContain("4111");
        assertThat(r.toJsonLine()).doesNotContain("4111");
    }

    @Test
    void errorEvent_journalsCodeAndOperationAndMessage() {
        Map<String, Object> props = new HashMap<>();
        props.put("code", "UPC_NOT_FOUND");
        props.put("operation", "addItemByUpc");
        props.put("message", "unknown UPC: xyz");
        props.put("upc", "xyz");
        pos.dispatchPosEvent(new PosEvent(PosEventType.ERROR, props));

        JournalRecord r = journal.lastOf("ERROR");
        assertThat(r.getFields()).containsEntry("code", "UPC_NOT_FOUND");
        assertThat(r.getFields()).containsEntry("operation", "addItemByUpc");
        assertThat(r.getFields()).containsEntry("upc", "xyz");
    }

    @Test
    void scanEvents_areDistinguishedByEventTypeAndSource() {
        Map<String, Object> scan = new HashMap<>();
        scan.put("upc", "012345678905");
        scan.put("source", "scan");
        pos.dispatchPosEvent(new PosEvent(PosEventType.ITEM_SCANNED, scan));

        Map<String, Object> manual = new HashMap<>();
        manual.put("upc", "012345678905");
        manual.put("source", "manualScan");
        pos.dispatchPosEvent(new PosEvent(PosEventType.ITEM_SCANNED, manual));

        List<JournalRecord> scans = journal.allOf("ITEM_SCANNED");
        assertThat(scans).hasSize(2);
        assertThat(scans.get(0).getFields()).containsEntry("source", "scan");
        assertThat(scans.get(1).getFields()).containsEntry("source", "manualScan");
    }

    @Test
    void remoteJournalSender_neverRunsOnTheEdt_evenWhenEnqueuedFromTheEdt() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean sawEdt = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean sawWrite = new java.util.concurrent.atomic.AtomicBoolean(false);
        RemoteJournal.Connector edtChecking = (h, p, t) -> new java.net.Socket() {
            @Override public java.io.OutputStream getOutputStream() {
                return new java.io.OutputStream() {
                    @Override public void write(int b) {
                        if (SwingUtilities.isEventDispatchThread()) sawEdt.set(true);
                        sawWrite.set(true);
                    }
                    @Override public void write(byte[] bs, int off, int len) {
                        if (SwingUtilities.isEventDispatchThread()) sawEdt.set(true);
                        sawWrite.set(true);
                    }
                    @Override public void flush() {}
                };
            }
            @Override public synchronized void close() {}
        };

        LocalJournal local = new LocalJournal(new java.io.PrintStream(
                new java.io.ByteArrayOutputStream(), true, java.nio.charset.StandardCharsets.UTF_8));
        RemoteJournal remote = new RemoteJournal("localhost", 65535, local,
                edtChecking, ms -> {}, 50);
        try {
            PosComponent local2 = new PosComponent(
                    new InMemoryItemRepository(Map.of(WIDGET.getUpc(), WIDGET)),
                    new TaxService(BigDecimal.ZERO), "STORE", 1, false);
            local2.addController(new JournalListener(remote));
            local2.start();

            SwingUtilities.invokeAndWait(() -> {
                local2.getTransactionService().startTransaction();
                LineItem li = local2.getTransactionService().addItemByUpc(WIDGET.getUpc(), 1);
                Map<String, Object> props = new HashMap<>();
                props.put("lineItem", li);
                local2.dispatchPosEvent(new PosEvent(PosEventType.ITEM_ADDED, props));
            });

            org.awaitility.Awaitility.await()
                    .atMost(java.time.Duration.ofSeconds(2))
                    .until(sawWrite::get);
            assertThat(sawEdt.get()).as("journal writes must never touch the Swing EDT").isFalse();
        } finally {
            remote.close();
        }
    }

    // ---- test helpers ----

    private static final class CapturingJournal implements Journal {
        final List<JournalRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void journal(JournalRecord record) {
            records.add(record);
        }

        JournalRecord lastOf(String event) {
            JournalRecord last = null;
            for (JournalRecord r : records) if (event.equals(r.getEvent())) last = r;
            assertThat(last).as("no record with event " + event).isNotNull();
            return last;
        }

        List<JournalRecord> allOf(String event) {
            List<JournalRecord> out = new java.util.ArrayList<>();
            for (JournalRecord r : records) if (event.equals(r.getEvent())) out.add(r);
            return out;
        }
    }
}
