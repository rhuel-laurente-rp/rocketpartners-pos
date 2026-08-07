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
import java.util.ArrayList;
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
    private JournalListener listener;

    @BeforeEach
    void setUp() {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);
        pos = new PosComponent(
                new InMemoryItemRepository(items),
                new TaxService(BigDecimal.ZERO),
                "STORE-01", 1, false);
        journal = new CapturingJournal();
        listener = new JournalListener(journal);
        pos.addController(listener);
        pos.start();
    }

    @Test
    void posStarted_isJournaledOnStart() {
        assertThat(journal.entries).anyMatch(e -> e.contains("POS_STARTED"));
        assertThat(journal.entries.get(0)).contains("STORE-01").contains("LANE-1");
    }

    @Test
    void itemAddedEvent_isJournaled_withUpcQtyDescExt() {
        pos.getTransactionService().startTransaction();
        LineItem li = pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), 2);
        Map<String, Object> props = new HashMap<>();
        props.put("lineItem", li);
        pos.dispatchPosEvent(new PosEvent(PosEventType.ITEM_ADDED, props));

        String entry = journal.lastMatching("ITEM_ADDED");
        assertThat(entry).contains("upc=\"012345678905\"");
        assertThat(entry).contains("qty=2");
        assertThat(entry).contains("desc=\"Widget\"");
        assertThat(entry).contains("ext=5.00");
    }

    @Test
    void cashTenderedEvent_journalsTenderTypeAndAmountsNotInstrumentDetails() {
        Map<String, Object> props = new HashMap<>();
        props.put("tenderType", TenderType.CASH);
        props.put("amountTendered", new BigDecimal("20.00"));
        props.put("changeDue", new BigDecimal("2.50"));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_TENDERED, props));

        String entry = journal.lastMatching("CASH_TENDERED");
        assertThat(entry).contains("tender=CASH");
        assertThat(entry).contains("amount=20.00");
        assertThat(entry).contains("change=2.50");
    }

    @Test
    void cardTenderedEvent_doesNotContainPaymentInstrumentPropertiesEvenIfSupplied() {
        // Even if the event carried spurious card data (it shouldn't, and this event type
        // does not), the listener must not journal it.
        Map<String, Object> props = new HashMap<>();
        props.put("tenderType", TenderType.CREDIT);
        props.put("amountTendered", new BigDecimal("100.00"));
        props.put("changeDue", BigDecimal.ZERO);
        props.put("cardNumber", "4111-1111-1111-1111"); // never journaled
        pos.dispatchPosEvent(new PosEvent(PosEventType.CARD_TENDERED, props));

        String entry = journal.lastMatching("CARD_TENDERED");
        assertThat(entry).contains("tender=CREDIT").contains("amount=100.00");
        assertThat(entry).doesNotContain("4111");
        assertThat(entry).doesNotContain("cardNumber");
    }

    @Test
    void errorEvent_journalsCodeAndOperationAndMessage() {
        Map<String, Object> props = new HashMap<>();
        props.put("code", "UPC_NOT_FOUND");
        props.put("operation", "addItemByUpc");
        props.put("message", "unknown UPC: xyz");
        props.put("upc", "xyz");
        pos.dispatchPosEvent(new PosEvent(PosEventType.ERROR, props));

        String entry = journal.lastMatching("ERROR");
        assertThat(entry).contains("code=UPC_NOT_FOUND");
        assertThat(entry).contains("operation=addItemByUpc");
        assertThat(entry).contains("message=");
        assertThat(entry).contains("upc=\"xyz\"");
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

        List<String> scans = new ArrayList<>();
        for (String e : journal.entries) if (e.contains("ITEM_SCANNED")) scans.add(e);
        assertThat(scans).hasSize(2);
        assertThat(scans.get(0)).contains("source=scan");
        assertThat(scans.get(1)).contains("source=manualScan");
    }

    @Test
    void remoteJournalSender_neverRunsOnTheEdt_evenWhenEnqueuedFromTheEdt() throws Exception {
        // A stubbed sender that captures whether it was called on the Swing EDT. Because
        // RemoteJournal offloads to a single daemon thread, this must always be false.
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
            JournalListener l = new JournalListener(remote);
            local2.addController(l);
            local2.start();

            // Dispatch an event on the EDT — the classic "cashier presses button" path.
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
        final List<String> entries = new CopyOnWriteArrayList<>();

        @Override
        public void journal(String entry) {
            entries.add(entry);
        }

        String lastMatching(String needle) {
            String last = null;
            for (String e : entries) if (e.contains(needle)) last = e;
            assertThat(last).as("no entry matches " + needle + "; entries=" + entries).isNotNull();
            return last;
        }
    }

}
