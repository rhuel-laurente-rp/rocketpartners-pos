package com.rocketpartners.onboarding.possystem.repository;

import com.rocketpartners.onboarding.commons.model.Item;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parser for the tab-separated pricebook format shared by every {@link ItemRepository}
 * implementation. Rows are {@code UPC \t description \t price}, no header, no comments; empty
 * lines are skipped. Malformed rows, duplicate UPCs, and unparseable prices are fatal and abort
 * parsing with an {@link IllegalStateException} that names the 1-based line number.
 */
public final class PricebookTsv {

    private PricebookTsv() {}

    /**
     * Reads a pricebook from a classpath resource. The path is resolved by
     * {@link Class#getResourceAsStream(String)} — use a leading {@code "/"} for classloader-root
     * paths (e.g. {@code "/pricebook.tsv"}).
     */
    public static Map<String, Item> loadFromClasspath(String resourcePath) {
        InputStream stream = PricebookTsv.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IllegalStateException("pricebook resource not found: " + resourcePath);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return parse(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read pricebook resource: " + resourcePath, e);
        }
    }

    /** Reads a pricebook from a filesystem path. */
    public static Map<String, Item> loadFromPath(Path path) {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return parse(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read pricebook file: " + path, e);
        }
    }

    public static Map<String, Item> parse(BufferedReader reader) throws IOException {
        Map<String, Item> map = new LinkedHashMap<>();
        int lineNumber = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isEmpty()) continue;

            String[] parts = line.split("\t", -1);
            if (parts.length != 3) {
                throw new IllegalStateException(
                        "malformed pricebook row at line " + lineNumber
                                + ": expected 3 tab-separated fields, got " + parts.length);
            }

            String upc = parts[0].trim();
            if (upc.isEmpty()) {
                throw new IllegalStateException("empty UPC at line " + lineNumber);
            }

            BigDecimal price;
            try {
                price = new BigDecimal(parts[2].trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "unparseable price at line " + lineNumber + ": '" + parts[2] + "'", e);
            }

            if (map.containsKey(upc)) {
                throw new IllegalStateException(
                        "duplicate UPC '" + upc + "' at line " + lineNumber);
            }

            map.put(upc, new Item(upc, parts[1], price));
        }
        return map;
    }
}
