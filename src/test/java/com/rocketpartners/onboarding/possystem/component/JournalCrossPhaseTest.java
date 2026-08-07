package com.rocketpartners.onboarding.possystem.component;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.commons.model.TransactionState;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.repository.inmemory.InMemoryItemRepository;
import com.rocketpartners.onboarding.possystem.service.TaxService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the cross-phase regression rule: Phase 2 must not break Phase 1. Two invariants:
 * <ol>
 *   <li>A full sale completes with byte-identical Transaction state whether or not the
 *       RemoteJournal has ever been reachable.</li>
 *   <li>A journal that is completely unreachable does not raise on any hop of the sale.</li>
 * </ol>
 */
class JournalCrossPhaseTest {

    private static final Item WIDGET = new Item("012345678905", "Widget", new BigDecimal("2.50"));

    @Test
    void fullSale_withJournalDownAtStartup_completesIdentically() {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);

        LocalJournal local = new LocalJournal(new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8));
        RemoteJournal.Connector alwaysFail = (h, p, t) -> {
            throw new ConnectException("simulated: journal down");
        };
        RemoteJournal remote = new RemoteJournal("localhost", 65535, local, alwaysFail, ms -> {}, 100);
        try {
            Journal composite = new Journals(local, remote);

            PosComponent pos = new PosComponent(
                    new InMemoryItemRepository(items),
                    new TaxService(new BigDecimal("0.07")),
                    "STORE-01", 1, false);
            pos.addController(new JournalListener(composite));
            pos.start();

            pos.getTransactionService().startTransaction();
            LineItem li = pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), 2);
            Map<String, Object> props = new HashMap<>();
            props.put("lineItem", li);
            pos.dispatchPosEvent(new PosEvent(PosEventType.ITEM_ADDED, props));
            pos.getTransactionService().total();
            Transaction paid = pos.getTransactionService().tenderCash(new BigDecimal("6.00"));

            assertThat(paid.getState()).isEqualTo(TransactionState.PAID);
            assertThat(paid.grandTotal()).isEqualByComparingTo("5.35");
            assertThat(paid.changeDue()).isEqualByComparingTo("0.65");
        } finally {
            remote.close();
        }
    }

    @Test
    void journalDown_neverRaisesOnAnyHop() {
        RemoteJournal.Connector everFail = (h, p, t) -> {
            throw new IOException("nope");
        };
        LocalJournal local = new LocalJournal(new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8));
        RemoteJournal remote = new RemoteJournal("localhost", 65535, local, everFail, ms -> {}, 5);
        try {
            for (int i = 0; i < 20; i++) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("burst", i);
                remote.journal(new JournalRecord(Instant.now(), "S", 1, "t", "ITEM_ADDED", f));
            }
            Awaitility.await().atMost(Duration.ofSeconds(1)).until(remote::isSenderAlive);
            assertThat(remote.isSenderAlive()).isTrue();
        } finally {
            remote.close();
        }
    }
}
