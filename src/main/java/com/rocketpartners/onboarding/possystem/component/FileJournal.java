package com.rocketpartners.onboarding.possystem.component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Appends each {@link JournalRecord} as one JSON object per line (JSON Lines) to
 * {@code <logDir>/journal-YYYY-MM-DD.jsonl}. The file is created lazily on the first write and
 * rolls over automatically at UTC midnight.
 *
 * <p>This journal exists so the developer has a queryable, always-on record of everything the
 * POS ever did, independent of whether the terminal was running the {@code runJournal} server.
 * The file is line-oriented so it plays nicely with {@code tail -f}, {@code grep}, and
 * {@code jq}.</p>
 *
 * <p>All I/O happens synchronously on the caller's thread. That is safe for the Swing EDT
 * because the write is bounded (one buffered line + flush) and there is no network involved;
 * for the size of writes the POS produces it is well under a millisecond. If that ever changes,
 * this class can migrate to the same queue+sender shape {@link RemoteJournal} uses.</p>
 */
public class FileJournal implements Journal {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path logDir;

    private String currentDate;
    private Path currentFile;
    private BufferedWriter writer;
    private boolean warnedOpenFailure;

    /**
     * @param logDir directory to write log files into; must not be {@code null}
     */
    public FileJournal(Path logDir) {
        if (logDir == null) throw new IllegalArgumentException("logDir must not be null");
        this.logDir = logDir;
    }

    @Override
    public synchronized void journal(JournalRecord record) {
        if (record == null) return;
        try {
            rollIfNewDay();
            writer.write(record.toJsonLine());
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            // Best-effort: report once, then silently drop until things recover.
            if (!warnedOpenFailure) {
                System.err.println("[journal] file journal write failed: " + e.getMessage());
                warnedOpenFailure = true;
            }
            closeWriterQuietly();
        }
    }

    private void rollIfNewDay() throws IOException {
        String today = DATE.format(LocalDate.now(ZoneOffset.UTC));
        if (today.equals(currentDate) && writer != null) return;
        closeWriterQuietly();
        Files.createDirectories(logDir);
        currentDate = today;
        currentFile = logDir.resolve("journal-" + today + ".jsonl");
        writer = new BufferedWriter(newWriter(currentFile));
        warnedOpenFailure = false;
    }

    private static Writer newWriter(Path file) throws IOException {
        return new java.io.OutputStreamWriter(
                Files.newOutputStream(file,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND),
                StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void close() {
        closeWriterQuietly();
    }

    private void closeWriterQuietly() {
        if (writer == null) return;
        try {
            writer.flush();
            writer.close();
        } catch (IOException ignored) {
            // Best-effort.
        }
        writer = null;
    }

    /** @return the file currently being written (or {@code null} if not yet opened) */
    public synchronized Path getCurrentFile() {
        return currentFile;
    }
}
