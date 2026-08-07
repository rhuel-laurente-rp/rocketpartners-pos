package com.rocketpartners.onboarding.posvirtualjournal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JournalPrinterTest {

    @Test
    void printPrependsTimestampAndPeer() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Clock fixed = Clock.fixed(Instant.parse("2026-08-07T14:32:05.412Z"), ZoneOffset.UTC);
        JournalPrinter p = new JournalPrinter(new PrintStream(buf, true, StandardCharsets.UTF_8), fixed);

        p.print("/127.0.0.1:5555", "STORE-01 | LANE-1 | txn-abc | ITEM_ADDED");

        String line = buf.toString(StandardCharsets.UTF_8).trim();
        assertThat(line).contains("2026-08-07T14:32:05.412Z");
        assertThat(line).contains("/127.0.0.1:5555");
        assertThat(line).contains("ITEM_ADDED");
    }

    @Test
    void concurrentPrintsAreNotInterleaved() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JournalPrinter p = new JournalPrinter(new PrintStream(buf, true, StandardCharsets.UTF_8),
                Clock.systemUTC());

        int writers = 8;
        int perWriter = 100;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int w = 0; w < writers; w++) {
                final int id = w;
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perWriter; i++) {
                        p.print("writer-" + id, "line-" + i);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        String[] lines = buf.toString(StandardCharsets.UTF_8).split("\n");
        assertThat(lines).hasSize(writers * perWriter);
        for (String line : lines) {
            // Each printed line contains exactly one "writer-N" tag and one "line-M" body.
            long tagCount = countOccurrences(line, "writer-");
            long bodyCount = countOccurrences(line, "line-");
            assertThat(tagCount).as("garbled line: %s", line).isEqualTo(1);
            assertThat(bodyCount).as("garbled line: %s", line).isEqualTo(1);
        }
    }

    private static long countOccurrences(String haystack, String needle) {
        long n = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }
}
