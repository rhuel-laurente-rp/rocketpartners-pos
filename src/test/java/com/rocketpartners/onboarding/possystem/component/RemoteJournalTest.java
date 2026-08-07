package com.rocketpartners.onboarding.possystem.component;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteJournalTest {

    private RemoteJournal remote;
    private ByteArrayOutputStream localBuf;

    @AfterEach
    void tearDown() {
        if (remote != null) remote.close();
    }

    private LocalJournal newLocal() {
        localBuf = new ByteArrayOutputStream();
        return new LocalJournal(new PrintStream(localBuf, true, StandardCharsets.UTF_8));
    }

    private String localOut() {
        return localBuf.toString(StandardCharsets.UTF_8);
    }

    private static JournalRecord rec(String event, String body) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (body != null) fields.put("body", body);
        return new JournalRecord(Instant.now(), "STORE", 1, "test", event, fields);
    }

    @Test
    void serverUnreachable_journalCallsDoNotBlockOrThrow() {
        LocalJournal local = newLocal();
        RemoteJournal.Connector broken = (h, p, t) -> {
            throw new ConnectException("connection refused");
        };
        RemoteJournal.Sleeper noSleep = ms -> {};
        remote = new RemoteJournal("localhost", 65535, local, broken, noSleep, 10);

        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) remote.journal(rec("ITEM_ADDED", "entry-" + i));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs).as("journal() must not block the caller").isLessThan(50);

        Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> localOut().contains("JOURNAL_UNREACHABLE"));
    }

    @Test
    void midSessionDisconnect_thenReconnect_resumesSending() throws Exception {
        LocalJournal local = newLocal();
        AtomicInteger connectCalls = new AtomicInteger();
        List<ByteArrayOutputStream> capturedStreams = new ArrayList<>();
        RemoteJournal.Connector staged = (h, p, t) -> {
            int n = connectCalls.incrementAndGet();
            if (n == 1) {
                return failableSocket(capturedStreams);
            } else if (n == 2 || n == 3) {
                throw new ConnectException("simulated outage attempt " + n);
            } else {
                return failableSocket(capturedStreams);
            }
        };
        remote = new RemoteJournal("localhost", 12345, local, staged, ms -> {}, 20);

        remote.journal(rec("ITEM_ADDED", "first"));
        Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> localOut().contains("JOURNAL_CONNECTED")
                        && !capturedStreams.isEmpty()
                        && new String(capturedStreams.get(0).toByteArray(), StandardCharsets.UTF_8)
                                .contains("first"));

        forceStreamFail(capturedStreams.get(0));
        remote.journal(rec("ITEM_ADDED", "second"));
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> {
                    if (capturedStreams.size() < 2) return false;
                    String s = new String(capturedStreams.get(capturedStreams.size() - 1).toByteArray(),
                            StandardCharsets.UTF_8);
                    return s.contains("second");
                });
        assertThat(localOut()).contains("JOURNAL_DISCONNECTED");
    }

    @Test
    void queueFullDropsRatherThanBlocks_andDroppedCountIsReported() {
        LocalJournal local = newLocal();
        CountDownLatch releaseConnect = new CountDownLatch(1);
        AtomicReference<ByteArrayOutputStream> streamRef = new AtomicReference<>();
        RemoteJournal.Connector slow = (h, p, t) -> {
            try {
                releaseConnect.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            streamRef.set(sink);
            return sinkSocket(sink);
        };
        remote = new RemoteJournal("localhost", 12345, local, slow, ms -> {}, 3);

        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) remote.journal(rec("ITEM_ADDED", "burst-" + i));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs).as("journal() must never block").isLessThan(100);
        assertThat(remote.droppedCount()).isGreaterThan(0);

        releaseConnect.countDown();
        Awaitility.await().atMost(Duration.ofSeconds(3))
                .until(() -> localOut().contains("JOURNAL_DROPPED"));
    }

    @Test
    void entriesShipInOrder_underRapidSends() {
        LocalJournal local = newLocal();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RemoteJournal.Connector immediate = (h, p, t) -> sinkSocket(sink);
        remote = new RemoteJournal("localhost", 12345, local, immediate, ms -> {}, 500);
        int n = 200;
        for (int i = 0; i < n; i++) remote.journal(rec("ITEM_ADDED", "e-" + i));
        Awaitility.await().atMost(Duration.ofSeconds(3))
                .until(() -> new String(sink.toByteArray(), StandardCharsets.UTF_8)
                        .split("\n").length >= n);
        String[] lines = new String(sink.toByteArray(), StandardCharsets.UTF_8).split("\n");
        for (int i = 0; i < n; i++) {
            assertThat(lines[i]).contains("e-" + i);
        }
    }

    @Test
    void newlineInFieldValue_isSanitizedToSingleEntry() {
        LocalJournal local = newLocal();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RemoteJournal.Connector immediate = (h, p, t) -> sinkSocket(sink);
        remote = new RemoteJournal("localhost", 12345, local, immediate, ms -> {}, 100);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("desc", "Red\nBull");
        remote.journal(new JournalRecord(Instant.now(), "S", 1, "t", "ITEM_ADDED", fields));

        Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> new String(sink.toByteArray(), StandardCharsets.UTF_8).contains("Red"));
        String wire = new String(sink.toByteArray(), StandardCharsets.UTF_8);
        assertThat(wire).doesNotContain("\r");
        long newlines = wire.chars().filter(c -> c == '\n').count();
        assertThat(newlines).isEqualTo(1);
        assertThat(wire).contains("Red Bull");
    }

    @Test
    void oversizedInput_isTruncated_notSplit() {
        StringBuilder huge = new StringBuilder(RemoteJournal.MAX_ENTRY_CHARS + 100);
        for (int i = 0; i < RemoteJournal.MAX_ENTRY_CHARS + 50; i++) huge.append('A');
        String out = RemoteJournal.sanitize(huge.toString());
        assertThat(out).hasSizeLessThanOrEqualTo(RemoteJournal.MAX_ENTRY_CHARS);
        assertThat(out).endsWith(RemoteJournal.TRUNCATION_MARKER);
    }

    // ---- test-only socket helpers ----------------------------------------

    private static Socket sinkSocket(ByteArrayOutputStream sink) throws IOException {
        return new Socket() {
            @Override public OutputStream getOutputStream() { return sink; }
            @Override public synchronized void close() {}
        };
    }

    private static Socket failableSocket(List<ByteArrayOutputStream> tracker) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream() {
            volatile boolean failing = false;
            @Override public synchronized void write(int b) {
                if (failing) throw new RuntimeException("simulated broken pipe");
                super.write(b);
            }
            @Override public synchronized void write(byte[] b, int off, int len) {
                if (failing) throw new RuntimeException("simulated broken pipe");
                super.write(b, off, len);
            }
        };
        tracker.add(sink);
        return new Socket() {
            @Override public OutputStream getOutputStream() {
                return new OutputStream() {
                    @Override public void write(int b) throws IOException {
                        try {
                            sink.write(b);
                        } catch (RuntimeException e) {
                            throw new IOException(e.getMessage(), e);
                        }
                    }
                    @Override public void write(byte[] b, int off, int len) throws IOException {
                        try {
                            sink.write(b, off, len);
                        } catch (RuntimeException e) {
                            throw new IOException(e.getMessage(), e);
                        }
                    }
                    @Override public void flush() {}
                };
            }
            @Override public synchronized void close() {}
        };
    }

    private static void forceStreamFail(ByteArrayOutputStream sink) {
        try {
            java.lang.reflect.Field f = sink.getClass().getDeclaredField("failing");
            f.setAccessible(true);
            f.setBoolean(sink, true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
