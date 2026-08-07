package com.rocketpartners.onboarding.possystem.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileJournalTest {

    @Test
    void appendsJsonPerLine_toDatedFile(@TempDir Path dir) throws IOException {
        FileJournal fj = new FileJournal(dir);
        try {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("upc", "012345678905");
            fields.put("qty", 2);
            fj.journal(new JournalRecord(Instant.parse("2026-08-07T14:32:05.412Z"),
                    "STORE-01", 1, "abc12345", "ITEM_ADDED", fields));

            Map<String, Object> f2 = new LinkedHashMap<>();
            f2.put("code", "UPC_NOT_FOUND");
            f2.put("message", "unknown UPC: nope");
            fj.journal(new JournalRecord(Instant.parse("2026-08-07T14:32:06.412Z"),
                    "STORE-01", 1, "abc12345", "ERROR", f2));
        } finally {
            fj.close();
        }

        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.toList();
        }
        assertThat(files).hasSize(1);
        List<String> lines = Files.readAllLines(files.get(0));
        assertThat(lines).hasSize(2);
        // Each line is a valid JSON object with the expected keys.
        assertThat(lines.get(0)).startsWith("{").endsWith("}");
        assertThat(lines.get(0)).contains("\"event\":\"ITEM_ADDED\"");
        assertThat(lines.get(0)).contains("\"upc\":\"012345678905\"");
        assertThat(lines.get(0)).contains("\"qty\":2");
        assertThat(lines.get(1)).contains("\"event\":\"ERROR\"");
        assertThat(lines.get(1)).contains("\"code\":\"UPC_NOT_FOUND\"");
    }

    @Test
    void journalRecord_pipeAndJsonRenderNewlinesSafely() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("desc", "Red\nBull");
        JournalRecord r = new JournalRecord(Instant.parse("2026-08-07T14:32:05.412Z"),
                "S", 1, "t", "ITEM_ADDED", fields);
        // Pipe rendering flattens newlines to spaces.
        assertThat(r.toPipeDelimited()).doesNotContain("\n");
        assertThat(r.toPipeDelimited()).contains("Red Bull");
        // JSON rendering escapes them.
        assertThat(r.toJsonLine()).contains("\\n");
        assertThat(r.toJsonLine()).doesNotContain("Red\n"); // raw newline not present
    }
}
