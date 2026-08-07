package com.rocketpartners.onboarding.possystem.component;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    void serverUnreachable_journalCallsDoNotBlockOrThrow() throws Exception {
        LocalJournal local = newLocal();
        // Connector that always throws — as if the port is not open.
        RemoteJournal.Connector broken = (h, p, t) -> {
            throw new ConnectException("connection refused");
        };
        RemoteJournal.Sleeper noSleep = ms -> {};
        remote = new RemoteJournal("localhost", 65535, local, broken, noSleep, 10);

        // These calls happen "on the EDT" in real life; we simulate the invariant by calling
        // them from a background thread and asserting completion within a tight budget.
        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) remote.journal("entry-" + i);
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
        // First connect returns a socket whose stream writes fine; then the "server" is
        // simulated dead: second connect throws twice, then a third connect returns a fresh
        // sink so the pending entry ships.
        RemoteJournal.Connector staged = (h, p, t) -> {
            int n = connectCalls.incrementAndGet();
            if (n == 1) {
                return failableSocket(false, capturedStreams);
            } else if (n == 2 || n == 3) {
                throw new ConnectException("simulated outage attempt " + n);
            } else {
                return failableSocket(false, capturedStreams);
            }
        };
        RemoteJournal.Sleeper noSleep = ms -> {};
        remote = new RemoteJournal("localhost", 12345, local, staged, noSleep, 20);

        remote.journal("first");
        Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> localOut().contains("JOURNAL_CONNECTED")
                        && !capturedStreams.isEmpty()
                        && new String(capturedStreams.get(0).toByteArray(), StandardCharsets.UTF_8)
                                .contains("first"));

        // Force disconnect by flipping the stream to failing.
        forceStreamFail(capturedStreams.get(0));
        remote.journal("second");
        // Second write will fail → JOURNAL_DISCONNECTED, then reconnect fails twice, then
        // succeeds and ships "second".
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
    void queueFullDropsRatherThanBlocks_andDroppedCountIsReported() throws Exception {
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
        // Tiny queue capacity so we can fill it deterministically.
        remote = new RemoteJournal("localhost", 12345, local, slow, ms -> {}, 3);

        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) remote.journal("burst-" + i);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs).as("journal() must never block").isLessThan(100);
        // At least the last several offers must have been dropped since the sender is blocked
        // waiting for connect().
        assertThat(remote.droppedCount()).isGreaterThan(0);

        // Now let the sender through. The next successful send prints JOURNAL_DROPPED.
        releaseConnect.countDown();
        Awaitility.await().atMost(Duration.ofSeconds(3))
                .until(() -> localOut().contains("JOURNAL_DROPPED"));
    }

    @Test
    void entriesShipInOrder_underRapidSends() throws Exception {
        LocalJournal local = newLocal();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RemoteJournal.Connector immediate = (h, p, t) -> sinkSocket(sink);
        remote = new RemoteJournal("localhost", 12345, local, immediate, ms -> {}, 500);
        int n = 200;
        for (int i = 0; i < n; i++) remote.journal("e-" + i);
        Awaitility.await().atMost(Duration.ofSeconds(3))
                .until(() -> new String(sink.toByteArray(), StandardCharsets.UTF_8)
                        .split("\n").length >= n);
        String[] lines = new String(sink.toByteArray(), StandardCharsets.UTF_8).split("\n");
        for (int i = 0; i < n; i++) {
            assertThat(lines[i]).isEqualTo("e-" + i);
        }
    }

    @Test
    void newlineInInput_isSanitizedToSingleEntry() throws Exception {
        LocalJournal local = newLocal();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RemoteJournal.Connector immediate = (h, p, t) -> sinkSocket(sink);
        remote = new RemoteJournal("localhost", 12345, local, immediate, ms -> {}, 100);
        remote.journal("desc=\"Red\nBull\"");
        Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> new String(sink.toByteArray(), StandardCharsets.UTF_8).contains("Red"));
        // Only one wire line + trailing empty from split.
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

    /** Returns a Socket whose getOutputStream() writes to `sink`. */
    private static Socket sinkSocket(ByteArrayOutputStream sink) throws IOException {
        // Use a real Socket pair via localhost loopback would be nicer, but for these tests we
        // only need a Socket-like object. Since Socket is not final and its methods are
        // mostly overridable, we subclass.
        return new Socket() {
            @Override public OutputStream getOutputStream() { return sink; }
            @Override public synchronized void close() {}
        };
    }

    /** Returns a Socket whose stream may be flipped to failing mode. */
    private static Socket failableSocket(boolean startFailing, List<ByteArrayOutputStream> tracker) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream() {
            volatile boolean failing = startFailing;
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
        // reflectively flip the `failing` field of the anonymous subclass above.
        try {
            java.lang.reflect.Field f = sink.getClass().getDeclaredField("failing");
            f.setAccessible(true);
            f.setBoolean(sink, true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
