package com.rocketpartners.onboarding.possystem.component;

import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.commons.model.TenderType;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Subscribes to every {@link PosEventType} and translates each into a {@link JournalRecord}
 * sent to a {@link Journal}.
 *
 * <p>Only the store / lane / short transaction id are attached — money is rendered from
 * {@link BigDecimal} at scale 2 using {@link RoundingMode#HALF_UP}, matching the receipt.
 * <strong>Payment instrument details are never journaled.</strong> Card tender records the
 * tender type and total amount only.</p>
 */
public class JournalListener implements IController, IPosEventListener {

    private static final Set<PosEventType> LISTEN_TYPES =
            Collections.unmodifiableSet(EnumSet.allOf(PosEventType.class));

    private final Journal journal;
    private PosComponent parent;

    /**
     * @param journal the journal to forward records to; must not be {@code null}
     */
    public JournalListener(Journal journal) {
        if (journal == null) throw new IllegalArgumentException("journal must not be null");
        this.journal = journal;
    }

    // ---- IController ------------------------------------------------------

    @Override
    public void onStart(PosComponent parent) {
        this.parent = parent;
        parent.register(this);
        journal.journal(buildSystem("POS_STARTED"));
    }

    @Override
    public void onEnd() {
        journal.journal(buildSystem("POS_STOPPED"));
        if (parent != null) {
            parent.unregister(this);
            parent = null;
        }
        journal.close();
    }

    // ---- IPosEventListener ------------------------------------------------

    @Override
    public Set<PosEventType> getListeningEventTypes() {
        return LISTEN_TYPES;
    }

    @Override
    public void onPosEvent(PosEvent event) {
        try {
            JournalRecord record = build(event);
            if (record != null) journal.journal(record);
        } catch (RuntimeException e) {
            // A journal listener must NEVER let its own failure bubble back into the event
            // pipeline — that would take out the entire event dispatch for one bad format.
            System.err.println("[journal-listener] format failed for "
                    + event.getType() + ": " + e.getMessage());
        }
    }

    // ---- Building JournalRecords ------------------------------------------

    private JournalRecord build(PosEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fillFields(fields, event);
        return new JournalRecord(
                Instant.now(),
                parent == null ? "?" : safeString(parent.getStoreName()),
                parent == null ? 0 : parent.getLaneNumber(),
                currentTxnId(),
                event.getType().name(),
                fields);
    }

    private JournalRecord buildSystem(String label) {
        return new JournalRecord(
                Instant.now(),
                parent == null ? "?" : safeString(parent.getStoreName()),
                parent == null ? 0 : parent.getLaneNumber(),
                currentTxnId(),
                label,
                new LinkedHashMap<>());
    }

    private String currentTxnId() {
        if (parent == null) return "-";
        Transaction tx = parent.getTransactionService().getCurrentTransaction();
        return tx == null ? "-" : shortenTxnId(tx.getTransactionId());
    }

    private static String shortenTxnId(String id) {
        if (id == null) return "-";
        int end = Math.min(8, id.length());
        return id.substring(0, end);
    }

    private static void fillFields(Map<String, Object> fields, PosEvent event) {
        switch (event.getType()) {
            case ITEM_SCANNED -> {
                putIfNotNull(fields, "upc", event.getProperty("upc", String.class));
                putIfNotNull(fields, "source", event.getProperty("source", String.class));
            }
            case SCAN_SUBMIT_PRESSED ->
                    putIfNotNull(fields, "raw", event.getProperty("raw", String.class));
            case QUICK_ADD_PRESSED ->
                    putIfNotNull(fields, "upc", event.getProperty("upc", String.class));
            case ITEM_ADDED, LINE_VOIDED -> {
                LineItem li = event.getProperty("lineItem", LineItem.class);
                if (li != null) putLineItem(fields, li);
            }
            case QUANTITY_CHANGED -> {
                LineItem li = event.getProperty("lineItem", LineItem.class);
                if (li != null) {
                    fields.put("upc", li.getItem().getUpc());
                    Integer newQty = event.getProperty("newQuantity", Integer.class);
                    if (newQty != null) fields.put("newQty", newQty);
                    fields.put("ext", money(li.extendedTotal()));
                }
            }
            case CASH_TENDERED, CARD_TENDERED, TRANSACTION_COMPLETED -> {
                TenderType tender = event.getProperty("tenderType", TenderType.class);
                if (tender != null) fields.put("tender", tender.name());
                BigDecimal tendered = event.getProperty("amountTendered", BigDecimal.class);
                if (tendered != null) fields.put("amount", money(tendered));
                BigDecimal change = event.getProperty("changeDue", BigDecimal.class);
                if (change != null) fields.put("change", money(change));
            }
            case CASH_EXACT_PRESSED, CASH_NEXT_DOLLAR_PRESSED -> {
                BigDecimal prefill = event.getProperty("prefillAmount", BigDecimal.class);
                if (prefill != null) fields.put("prefill", money(prefill));
            }
            case BASKET_VOIDED -> {
                // A confirmed void records what was discarded (item count and grand total) plus
                // the state the transaction was in beforehand. Voiding after Total is the more
                // interesting case operationally, so priorState makes the two paths
                // distinguishable when a shrink review pulls the log.
                Integer itemCount = event.getProperty("itemCount", Integer.class);
                if (itemCount != null) fields.put("itemCount", itemCount);
                BigDecimal grandTotal = event.getProperty("grandTotal", BigDecimal.class);
                if (grandTotal != null) fields.put("grandTotal", money(grandTotal));
                putIfNotNull(fields, "priorState", event.getProperty("priorState", String.class));
            }
            case VOID_BASKET_DECLINED -> {
                // A declined void — the cashier opened the confirmation and backed out. Costs
                // one line to capture and is exactly the pattern a shrink review looks for on
                // a lane that racks up repeated near-voids.
                Integer itemCount = event.getProperty("itemCount", Integer.class);
                if (itemCount != null) fields.put("itemCount", itemCount);
                BigDecimal grandTotal = event.getProperty("grandTotal", BigDecimal.class);
                if (grandTotal != null) fields.put("grandTotal", money(grandTotal));
            }
            case ERROR -> {
                putIfNotNull(fields, "code", event.getProperty("code", String.class));
                putIfNotNull(fields, "message", event.getProperty("message", String.class));
                putIfNotNull(fields, "operation", event.getProperty("operation", String.class));
                putIfNotNull(fields, "raw", event.getProperty("raw", String.class));
                putIfNotNull(fields, "upc", event.getProperty("upc", String.class));
            }
            default -> {
                // No interesting body — the event name alone is the record.
            }
        }
    }

    private static void putLineItem(Map<String, Object> fields, LineItem li) {
        fields.put("upc", li.getItem().getUpc());
        fields.put("qty", li.getQuantity());
        fields.put("desc", li.getItem().getDescription());
        fields.put("ext", money(li.extendedTotal()));
    }

    private static void putIfNotNull(Map<String, Object> fields, String key, Object value) {
        if (value != null) fields.put(key, value);
    }

    private static String money(BigDecimal amount) {
        if (amount == null) return "0.00";
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String safeString(String s) {
        return s == null ? "?" : s;
    }
}
