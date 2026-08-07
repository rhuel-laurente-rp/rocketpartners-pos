package com.rocketpartners.onboarding.posvirtualjournal;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

class POSVirtualJournalTest {

    private ByteArrayOutputStream buf;
    private POSVirtualJournal server;

    @BeforeEach
    void setUp() {
        buf = new ByteArrayOutputStream();
        JournalPrinter printer = new JournalPrinter(
                new PrintStream(buf, true, StandardCharsets.UTF_8), Clock.systemUTC());
        // Port 0 → OS picks an ephemeral port; getBoundPort() returns the real port after bind.
        server = new POSVirtualJournal(0, printer);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private String stdout() {
        return buf.toString(StandardCharsets.UTF_8);
    }

    private Socket connect() throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", server.getBoundPort()), 2_000);
        return s;
    }

    private static Writer writer(Socket s) throws Exception {
        return new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
    }

    @Test
    void endToEnd_sendOneLine_serverPrintsIt() throws Exception {
        server.start();
        try (Socket s = connect(); Writer w = writer(s)) {
            w.write("STORE-01 | LANE-1 | txn-abc | ITEM_ADDED | upc=012345678905\n");
            w.flush();
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .until(() -> stdout().contains("ITEM_ADDED"));
        }
        assertThat(stdout()).contains("upc=012345678905");
    }

    @Test
    void twoConcurrentClients_bothDelivered_notGarbled() throws Exception {
        server.start();
        Socket a = connect();
        Socket b = connect();
        try (Writer wa = writer(a); Writer wb = writer(b)) {
            wa.write("LINE-FROM-A\n");
            wa.flush();
            wb.write("LINE-FROM-B\n");
            wb.flush();
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .until(() -> stdout().contains("LINE-FROM-A") && stdout().contains("LINE-FROM-B"));
        }
        // Each is on its own line and untangled.
        String out = stdout();
        for (String line : out.split("\n")) {
            if (line.contains("LINE-FROM-A")) assertThat(line).doesNotContain("LINE-FROM-B");
            if (line.contains("LINE-FROM-B")) assertThat(line).doesNotContain("LINE-FROM-A");
        }
        a.close();
        b.close();
    }

    @Test
    void abruptDisconnect_leavesServerAcceptingSubsequentConnections() throws Exception {
        server.start();
        Socket rude = connect();
        rude.close(); // no read, no write, just yank.
        // Server should keep accepting.
        try (Socket s = connect(); Writer w = writer(s)) {
            w.write("AFTER-DISCONNECT\n");
            w.flush();
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .until(() -> stdout().contains("AFTER-DISCONNECT"));
        }
    }

    @Test
    void oversizedLine_discarded_handlerSurvives_stillAcceptsFurtherLines() throws Exception {
        server.start();
        try (Socket s = connect(); Writer w = writer(s)) {
            StringBuilder huge = new StringBuilder(ClientHandler.MAX_ENTRY_CHARS + 10);
            for (int i = 0; i < ClientHandler.MAX_ENTRY_CHARS + 5; i++) huge.append('X');
            w.write(huge.toString());
            w.write('\n');
            w.write("PARTNER-LINE\n");
            w.flush();
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .until(() -> stdout().contains("PARTNER-LINE"));
            // Oversized line was NOT printed.
            assertThat(stdout()).doesNotContain("XXXXXXXX");
        }
    }

    @Test
    void shutdown_closesServerAndTerminatesAcceptLoop() throws Exception {
        server.start();
        int port = server.getBoundPort();
        server.stop();
        assertThat(server.isRunning()).isFalse();

        // Now a fresh bind on the same port must succeed (or at least, a connect to the old
        // port must fail). We use the latter — some OSes hold TIME_WAIT briefly.
        boolean refused = false;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
        } catch (Exception e) {
            refused = true;
        }
        assertThat(refused).isTrue();
    }

    @Test
    void bindErrorSurfaces_whenPortAlreadyInUse() throws Exception {
        server.start();
        int inUse = server.getBoundPort();
        POSVirtualJournal second = new POSVirtualJournal(inUse, new JournalPrinter(
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Clock.systemUTC()));
        try {
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, second::start);
        } finally {
            second.stop();
        }
    }
}
