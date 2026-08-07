package com.rocketpartners.onboarding.posvirtualjournal;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The idle-timeout path exercised end-to-end using a real {@link POSVirtualJournal} configured
 * with a very short per-connection read timeout.
 */
class ClientHandlerIdleTimeoutTest {

    private POSVirtualJournal server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void idleClient_timesOut_serverStillAcceptsNewClients() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JournalPrinter printer = new JournalPrinter(
                new PrintStream(buf, true, StandardCharsets.UTF_8), Clock.systemUTC());
        // 200 ms so the test finishes quickly without a real 30-second wait.
        server = new POSVirtualJournal(0, printer, 200);
        server.start();

        // Open an idle connection: connect and never write.
        try (Socket idle = new Socket()) {
            idle.connect(new InetSocketAddress("127.0.0.1", server.getBoundPort()), 2_000);

            // Wait for the server-side handler to give up on the idle client. We can't observe
            // it directly, but we can verify a second client's traffic still flows — meaning
            // the accept loop and executor are healthy.
            Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> {
                try (Socket active = new Socket()) {
                    active.connect(new InetSocketAddress("127.0.0.1", server.getBoundPort()), 500);
                    try (Writer w = new OutputStreamWriter(active.getOutputStream(),
                            StandardCharsets.UTF_8)) {
                        w.write("STILL-ALIVE\n");
                        w.flush();
                    }
                    // Give the printer a beat to flush; polling checks buf state next round.
                    return buf.toString(StandardCharsets.UTF_8).contains("STILL-ALIVE");
                } catch (Exception e) {
                    return false;
                }
            });
        }
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("STILL-ALIVE");
    }
}
