package com.rocketpartners.onboarding.possystem.component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One structured journal entry produced by {@link JournalListener}. Each {@link Journal}
 * implementation renders it however that sink needs:
 * <ul>
 *   <li>{@link LocalJournal} and {@link RemoteJournal} render it pipe-delimited (the wire format
 *       the {@link com.rocketpartners.onboarding.posvirtualjournal.POSVirtualJournal} server
 *       prints).</li>
 *   <li>{@link FileJournal} serializes it as one JSON object per line (JSON Lines).</li>
 * </ul>
 *
 * <p>Immutable and cheap to construct. Field ordering is preserved by using a
 * {@link LinkedHashMap} so JSON output is deterministic and pipe-delimited output is stable.</p>
 */
public final class JournalRecord {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final Instant timestamp;
    private final String store;
    private final int lane;
    private final String txnId;
    private final String event;
    private final Map<String, Object> fields;

    /**
     * @param timestamp UTC wall-clock time of the source event; must not be {@code null}
     * @param store     store name; may be {@code null} → rendered as {@code ?}
     * @param lane      lane number
     * @param txnId     transaction id (short); may be {@code null} → rendered as {@code -}
     * @param event     event name (typically {@link com.rocketpartners.onboarding.possystem.event.PosEventType}
     *                  or a system tag like {@code POS_STARTED}); must not be {@code null}
     * @param fields    ordered event-specific fields; may be empty; must not be {@code null}
     */
    public JournalRecord(Instant timestamp, String store, int lane, String txnId,
                         String event, Map<String, Object> fields) {
        if (timestamp == null) throw new IllegalArgumentException("timestamp must not be null");
        if (event == null) throw new IllegalArgumentException("event must not be null");
        if (fields == null) throw new IllegalArgumentException("fields must not be null");
        this.timestamp = timestamp;
        this.store = store;
        this.lane = lane;
        this.txnId = txnId;
        this.event = event;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public Instant getTimestamp() { return timestamp; }
    public String getStore() { return store; }
    public int getLane() { return lane; }
    public String getTxnId() { return txnId; }
    public String getEvent() { return event; }
    public Map<String, Object> getFields() { return fields; }

    /**
     * The pipe-delimited wire / console rendering:
     * <pre>2026-08-07T14:32:05.412Z | STORE-01 | LANE-1 | txn-8f3a1c | ITEM_ADDED | k=v k2=v2</pre>
     */
    public String toPipeDelimited() {
        StringBuilder sb = new StringBuilder(128);
        sb.append(TS_FORMAT.format(timestamp))
                .append(" | ").append(store == null ? "?" : store)
                .append(" | LANE-").append(lane)
                .append(" | txn-").append(txnId == null ? "-" : txnId)
                .append(" | ").append(event);
        if (!fields.isEmpty()) {
            sb.append(" |");
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(renderPipeValue(e.getValue()));
            }
        }
        return sb.toString();
    }

    /**
     * JSON Lines rendering: one JSON object per record on a single line. Hand-rolled to avoid
     * adding a serializer dependency for a shape this small; keys are known and safe.
     */
    public String toJsonLine() {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        appendJsonString(sb, "ts");
        sb.append(':');
        appendJsonString(sb, TS_FORMAT.format(timestamp));
        sb.append(',');
        appendJsonString(sb, "store");
        sb.append(':');
        appendJsonString(sb, store == null ? "?" : store);
        sb.append(',');
        appendJsonString(sb, "lane");
        sb.append(':').append(lane);
        sb.append(',');
        appendJsonString(sb, "txnId");
        sb.append(':');
        appendJsonString(sb, txnId == null ? "-" : txnId);
        sb.append(',');
        appendJsonString(sb, "event");
        sb.append(':');
        appendJsonString(sb, event);
        sb.append(',');
        appendJsonString(sb, "fields");
        sb.append(':');
        appendJsonObject(sb, fields);
        sb.append('}');
        return sb.toString();
    }

    private static String renderPipeValue(Object value) {
        if (value == null) return "\"\"";
        String s = String.valueOf(value);
        // Fold newlines so a rogue value can't split the entry.
        s = s.replace('\n', ' ').replace('\r', ' ');
        // Quote when the value contains whitespace or a pipe.
        if (s.indexOf(' ') >= 0 || s.indexOf('|') >= 0 || s.indexOf('=') >= 0 || s.isEmpty()) {
            return "\"" + s.replace('"', '\'') + "\"";
        }
        return s;
    }

    private static void appendJsonObject(StringBuilder sb, Map<String, Object> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            appendJsonString(sb, e.getKey());
            sb.append(':');
            appendJsonValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void appendJsonValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            appendJsonString(sb, String.valueOf(value));
        }
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
