package com.rocketpartners.onboarding.possystem.repository.inmemory;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.possystem.repository.ItemRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link ItemRepository} backed by a {@code Map} keyed on UPC.
 *
 * <p>Two ways to construct: pass an existing map (useful in tests), or use one of the static
 * factories to load a tab-separated pricebook from the classpath or a filesystem path. Rows are
 * {@code UPC \t description \t price}, no header, no comments. Malformed rows, duplicate UPCs,
 * and unparseable prices are fatal: parsing aborts with an {@link IllegalStateException} that
 * names the 1-based line number so the offending row is easy to find.</p>
 */
public class InMemoryItemRepository implements ItemRepository {

    private final Map<String, Item> itemsByUpc;

    /**
     * Constructs a repository backed by the given map. The map is defensively copied.
     *
     * @param itemsByUpc initial items keyed by UPC; must not be {@code null}
     */
    public InMemoryItemRepository(Map<String, Item> itemsByUpc) {
        if (itemsByUpc == null) throw new IllegalArgumentException("itemsByUpc must not be null");
        this.itemsByUpc = Collections.unmodifiableMap(new HashMap<>(itemsByUpc));
    }

    /**
     * Loads a pricebook from a classpath resource. The path is resolved by
     * {@link Class#getResourceAsStream(String)} — use a leading {@code "/"} for classloader-root
     * paths (e.g. {@code "/pricebook.tsv"}).
     *
     * @param resourcePath classpath location
     * @return a populated repository
     * @throws IllegalStateException if the resource is missing or contains malformed data
     */
    public static InMemoryItemRepository loadFromClasspath(String resourcePath) {
        InputStream stream = InMemoryItemRepository.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IllegalStateException("pricebook resource not found: " + resourcePath);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return new InMemoryItemRepository(parse(reader));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read pricebook resource: " + resourcePath, e);
        }
    }

    /**
     * Loads a pricebook from a filesystem path.
     *
     * @param path file to read; must not be {@code null}
     * @return a populated repository
     * @throws IllegalStateException if the file is unreadable or contains malformed data
     */
    public static InMemoryItemRepository loadFromPath(Path path) {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return new InMemoryItemRepository(parse(reader));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read pricebook file: " + path, e);
        }
    }

    static Map<String, Item> parse(BufferedReader reader) throws IOException {
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

    @Override
    public Optional<Item> findByUpc(String upc) {
        if (upc == null) return Optional.empty();
        return Optional.ofNullable(itemsByUpc.get(upc));
    }

    @Override
    public int size() {
        return itemsByUpc.size();
    }
}
