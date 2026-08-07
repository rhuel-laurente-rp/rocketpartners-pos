package com.rocketpartners.onboarding.possystem.tools;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;

/**
 * A small "tail -f" for the FileJournal's JSONL output. Cross-platform (no dependency on
 * {@code tail(1)}), and prints a compact one-line summary of each record as it arrives.
 *
 * <p>Usage: {@code ./gradlew tailJournalLog}. By default it watches
 * {@code logs/journal-*.jsonl}; pass {@code -Dlog.dir=path/to/logs} to change it.</p>
 */
public final class TailJournal {

    private TailJournal() {}

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(System.getProperty("log.dir", "logs")).toAbsolutePath();
        System.out.println("[tail] watching " + dir);
        Path file = waitForFile(dir);
        System.out.println("[tail] streaming " + file);

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            // Start from the end — only new lines print.
            raf.seek(raf.length());
            StringBuilder buf = new StringBuilder();
            while (!Thread.currentThread().isInterrupted()) {
                int b = raf.read();
                if (b < 0) {
                    Thread.sleep(200);
                    // Roll if the day's file changed.
                    Optional<Path> newest = findNewestJournal(dir);
                    if (newest.isPresent() && !newest.get().equals(file)) {
                        System.out.println("[tail] rolling to " + newest.get());
                        // Exit; a supervisor could re-invoke us. For simplicity we just switch.
                        raf.close();
                        streamFrom(newest.get());
                        return;
                    }
                    continue;
                }
                if (b == '\n') {
                    printLine(buf.toString());
                    buf.setLength(0);
                } else if (b != '\r') {
                    buf.append((char) b);
                }
            }
        }
    }

    private static void streamFrom(Path file) throws IOException, InterruptedException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            StringBuilder buf = new StringBuilder();
            while (!Thread.currentThread().isInterrupted()) {
                int b = raf.read();
                if (b < 0) {
                    Thread.sleep(200);
                    continue;
                }
                if (b == '\n') {
                    printLine(buf.toString());
                    buf.setLength(0);
                } else if (b != '\r') {
                    buf.append((char) b);
                }
            }
        }
    }

    private static void printLine(String jsonLine) {
        // We don't want a JSON parser dependency in main sources — do a tiny extraction of
        // the fields we care about with simple string search.
        String ts = extract(jsonLine, "\"ts\":\"", "\"");
        String store = extract(jsonLine, "\"store\":\"", "\"");
        String lane = extract(jsonLine, "\"lane\":", ",");
        String txnId = extract(jsonLine, "\"txnId\":\"", "\"");
        String event = extract(jsonLine, "\"event\":\"", "\"");
        String fields = extract(jsonLine, "\"fields\":", "}");
        // Cheap "print full JSON if the compact form failed"
        if (event == null || ts == null) {
            System.out.println(jsonLine);
            return;
        }
        System.out.println(String.format("%-30s %-10s LANE-%-3s txn-%-10s %-24s %s",
                ts, store == null ? "?" : store, lane == null ? "0" : lane.trim(),
                txnId == null ? "-" : txnId, event, fields == null ? "" : fields + "}"));
    }

    private static String extract(String s, String start, String end) {
        int a = s.indexOf(start);
        if (a < 0) return null;
        a += start.length();
        int b = s.indexOf(end, a);
        if (b < 0) return null;
        return s.substring(a, b);
    }

    private static Path waitForFile(Path dir) throws InterruptedException, IOException {
        Files.createDirectories(dir);
        while (!Thread.currentThread().isInterrupted()) {
            Optional<Path> f = findNewestJournal(dir);
            if (f.isPresent()) return f.get();
            System.out.println("[tail] no journal-*.jsonl yet — waiting…");
            Thread.sleep(1_000);
        }
        throw new InterruptedException();
    }

    private static Optional<Path> findNewestJournal(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "journal-*.jsonl")) {
            Path best = null;
            long bestMtime = Long.MIN_VALUE;
            for (Path p : stream) {
                long m = Files.getLastModifiedTime(p).toMillis();
                if (m > bestMtime) {
                    bestMtime = m;
                    best = p;
                }
            }
            return Optional.ofNullable(best);
        }
    }

    @SuppressWarnings("unused")
    private static String utf8(byte[] bs) {
        return new String(bs, StandardCharsets.UTF_8);
    }
}
