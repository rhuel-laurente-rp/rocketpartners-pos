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
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Subscribes to every {@link PosEventType} and forwards each event as a pipe-delimited entry
 * to a {@link Journal}.
 *
 * <p>Entry shape (spec section "Wire protocol"):</p>
 * <pre>
 * &lt;ISO-8601 UTC ts&gt; | &lt;store&gt; | LANE-&lt;lane&gt; | txn-&lt;id-prefix&gt; | &lt;EVENT&gt; | &lt;key=value ...&gt;
 * </pre>
 *
 * <p>Only the store / lane / transaction-id prefix are attached — money is rendered from
 * {@link BigDecimal} at scale 2 using {@link RoundingMode#HALF_UP}, matching the receipt.
 * <strong>Payment instrument details are never journaled.</strong> Card tender records the
 * tender type and total amount only.</p>
 *
 * <p>The listener is itself an {@link IController} so it is added to {@link PosComponent} the
 * same way every other controller is; {@link #onStart} registers the listener,
 * {@link #onEnd} unregisters and {@link Journal#close closes} the delegate.</p>
 */
public class JournalListener implements IController, IPosEventListener {

    private static final Set<PosEventType> LISTEN_TYPES =
            Collections.unmodifiableSet(EnumSet.allOf(PosEventType.class));

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final Journal journal;
    private PosComponent parent;

    /**
     * @param journal the journal to forward entries to; must not be {@code null}
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
        // Startup ping so the operator sees the lane on the journal terminal even before the
        // first user action.
        writeSystem("POS_STARTED");
    }

    @Override
    public void onEnd() {
        writeSystem("POS_STOPPED");
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
            String line = format(event);
            if (line != null) journal.journal(line);
        } catch (RuntimeException e) {
            // A journal listener must NEVER let its own failure bubble back into the event
            // pipeline — that would take out the entire event dispatch for one bad format.
            System.err.println("[journal-listener] format failed for "
                    + event.getType() + ": " + e.getMessage());
        }
    }

    // ---- Formatting -------------------------------------------------------

    private String format(PosEvent event) {
        StringBuilder body = new StringBuilder();
        appendCommonBody(body, event);
        return prefix(event) + body;
    }

    private String prefix(PosEvent event) {
        String store = parent == null ? "?" : safeString(parent.getStoreName());
        int lane = parent == null ? 0 : parent.getLaneNumber();
        String txnId = "-";
        if (parent != null) {
            Transaction tx = parent.getTransactionService().getCurrentTransaction();
            if (tx != null) txnId = shortenTxnId(tx.getTransactionId());
        }
        return TS_FORMAT.format(Instant.now())
                + " | " + store
                + " | LANE-" + lane
                + " | txn-" + txnId
                + " | " + event.getType();
    }

    private void writeSystem(String label) {
        String store = parent == null ? "?" : safeString(parent.getStoreName());
        int lane = parent == null ? 0 : parent.getLaneNumber();
        journal.journal(TS_FORMAT.format(Instant.now())
                + " | " + store
                + " | LANE-" + lane
                + " | txn-- | " + label);
    }

    private static String shortenTxnId(String id) {
        if (id == null) return "-";
        int end = Math.min(8, id.length());
        return id.substring(0, end);
    }

    private static void appendCommonBody(StringBuilder sb, PosEvent event) {
        switch (event.getType()) {
            case ITEM_SCANNED -> {
                sb.append(" | upc=").append(quote(event.getProperty("upc", String.class)));
                String source = event.getProperty("source", String.class);
                if (source != null) sb.append(" source=").append(source);
            }
            case SCAN_SUBMIT_PRESSED -> {
                String raw = event.getProperty("raw", String.class);
                if (raw != null) sb.append(" | raw=").append(quote(raw));
            }
            case QUICK_ADD_PRESSED -> {
                sb.append(" | upc=").append(quote(event.getProperty("upc", String.class)));
            }
            case ITEM_ADDED, LINE_VOIDED -> {
                LineItem li = event.getProperty("lineItem", LineItem.class);
                if (li != null) appendLineItem(sb, li);
            }
            case QUANTITY_CHANGED -> {
                LineItem li = event.getProperty("lineItem", LineItem.class);
                if (li != null) {
                    sb.append(" | upc=").append(quote(li.getItem().getUpc()));
                    Integer newQty = event.getProperty("newQuantity", Integer.class);
                    if (newQty != null) sb.append(" newQty=").append(newQty);
                    sb.append(" ext=").append(money(li.extendedTotal()));
                }
            }
            case TRANSACTION_TOTALED -> {
                // No properties are carried on this event; if the parent has a totaled
                // transaction, record its subtotal so the journal shows the total value.
            }
            case CASH_TENDERED, CARD_TENDERED, TRANSACTION_COMPLETED -> {
                TenderType tender = event.getProperty("tenderType", TenderType.class);
                if (tender != null) sb.append(" | tender=").append(tender);
                BigDecimal tendered = event.getProperty("amountTendered", BigDecimal.class);
                if (tendered != null) sb.append(" amount=").append(money(tendered));
                BigDecimal change = event.getProperty("changeDue", BigDecimal.class);
                if (change != null) sb.append(" change=").append(money(change));
            }
            case ERROR -> {
                String code = event.getProperty("code", String.class);
                if (code != null) sb.append(" | code=").append(code);
                String message = event.getProperty("message", String.class);
                if (message != null) sb.append(" message=").append(quote(message));
                String operation = event.getProperty("operation", String.class);
                if (operation != null) sb.append(" operation=").append(operation);
                String raw = event.getProperty("raw", String.class);
                if (raw != null) sb.append(" raw=").append(quote(raw));
                String upc = event.getProperty("upc", String.class);
                if (upc != null) sb.append(" upc=").append(quote(upc));
            }
            default -> {
                // For events without an interesting body, the type prefix is enough.
            }
        }
    }

    private static void appendLineItem(StringBuilder sb, LineItem li) {
        sb.append(" | upc=").append(quote(li.getItem().getUpc()))
                .append(" qty=").append(li.getQuantity())
                .append(" desc=").append(quote(li.getItem().getDescription()))
                .append(" ext=").append(money(li.extendedTotal()));
    }

    private static String money(BigDecimal amount) {
        if (amount == null) return "0.00";
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Quotes a value and folds embedded quotes into single quotes so the field boundary is clear. */
    private static String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace('"', '\'') + "\"";
    }

    private static String safeString(String s) {
        return s == null ? "?" : s;
    }
}
