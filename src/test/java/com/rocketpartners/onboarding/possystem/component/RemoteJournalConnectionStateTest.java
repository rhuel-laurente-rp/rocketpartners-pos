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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the {@link RemoteJournal.ConnectionListener} contract used by the CustomerView
 * header indicator: a listener sees DISCONNECTED on register (initial state), transitions to
 * CONNECTED on the first successful send, and back to DISCONNECTED on a write failure.
 */
class RemoteJournalConnectionStateTest {

    private RemoteJournal remote;

    @AfterEach
    void tearDown() {
        if (remote != null) remote.close();
    }

    private static LocalJournal silentLocal() {
        return new LocalJournal(new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8));
    }

    private static JournalRecord rec(String tag) {
        return new JournalRecord(Instant.now(), "S", 1, "t", "ITEM_ADDED",
                new LinkedHashMap<>() {{ put("body", tag); }});
    }

    @Test
    void freshInstance_reportsDisconnected_onRegister() {
        remote = new RemoteJournal("localhost", 65535, silentLocal(),
                (h, p, t) -> { throw new ConnectException("nope"); },
                ms -> {}, 5);
        List<RemoteJournal.ConnectionState> observed = new CopyOnWriteArrayList<>();
        remote.setConnectionListener(observed::add);
        assertThat(observed).containsExactly(RemoteJournal.ConnectionState.DISCONNECTED);
    }

    @Test
    void reachesConnected_thenGoesBackToDisconnected_onWriteFailure() {
        AtomicInteger connectCalls = new AtomicInteger();
        List<ByteArrayOutputStream> streams = new ArrayList<>();
        RemoteJournal.Connector staged = (h, p, t) -> {
            connectCalls.incrementAndGet();
            return failableSocket(streams);
        };
        remote = new RemoteJournal("localhost", 12345, silentLocal(), staged, ms -> {}, 10);

        List<RemoteJournal.ConnectionState> observed = new CopyOnWriteArrayList<>();
        remote.setConnectionListener(observed::add);

        remote.journal(rec("first"));
        Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> observed.contains(RemoteJournal.ConnectionState.CONNECTED));

        forceStreamFail(streams.get(0));
        remote.journal(rec("boom"));

        // After the write fails we expect to see a DISCONNECTED transition. The sender may
        // then immediately reconnect (a fresh failableSocket comes back CONNECTED), so we
        // count total DISCONNECTED observations: the initial one on register plus the one
        // triggered by the write failure = at least 2.
        Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> observed.stream()
                        .filter(s -> s == RemoteJournal.ConnectionState.DISCONNECTED)
                        .count() >= 2);
        assertThat(observed).contains(RemoteJournal.ConnectionState.CONNECTED);
    }

    @Test
    void unreachableAtStartup_neverTransitionsToConnected() throws Exception {
        remote = new RemoteJournal("localhost", 65535, silentLocal(),
                (h, p, t) -> { throw new IOException("nope"); },
                ms -> {}, 5);

        List<RemoteJournal.ConnectionState> observed = new CopyOnWriteArrayList<>();
        remote.setConnectionListener(observed::add);

        // Drive several sends — none will succeed.
        for (int i = 0; i < 3; i++) remote.journal(rec("attempt-" + i));

        // Give the sender time to retry a few times.
        Thread.sleep(200);
        assertThat(observed).doesNotContain(RemoteJournal.ConnectionState.CONNECTED);
    }

    // ---- helpers (mirroring the shape used in RemoteJournalTest) ----------

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
                        try { sink.write(b); }
                        catch (RuntimeException e) { throw new IOException(e.getMessage(), e); }
                    }
                    @Override public void write(byte[] b, int off, int len) throws IOException {
                        try { sink.write(b, off, len); }
                        catch (RuntimeException e) { throw new IOException(e.getMessage(), e); }
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
