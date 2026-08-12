package com.rocketpartners.onboarding.possystem.repository.inmemory;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.possystem.repository.ItemRepository;
import com.rocketpartners.onboarding.possystem.repository.PricebookTsv;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link ItemRepository} backed by a {@code Map} keyed on UPC. Kept for tests and
 * anywhere else that wants a pricebook without touching the disk.
 *
 * <p>Two ways to construct: pass an existing map (useful in tests), or use one of the static
 * factories to load a TSV pricebook via {@link PricebookTsv}. See that class for the row format
 * and error semantics.</p>
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

    /** Loads a pricebook from a classpath resource. See {@link PricebookTsv#loadFromClasspath}. */
    public static InMemoryItemRepository loadFromClasspath(String resourcePath) {
        return new InMemoryItemRepository(PricebookTsv.loadFromClasspath(resourcePath));
    }

    /** Loads a pricebook from a filesystem path. See {@link PricebookTsv#loadFromPath}. */
    public static InMemoryItemRepository loadFromPath(Path path) {
        return new InMemoryItemRepository(PricebookTsv.loadFromPath(path));
    }

    @Override
    public Optional<Item> findByUpc(String upc) {
        if (upc == null) return Optional.empty();
        return Optional.ofNullable(itemsByUpc.get(upc));
    }

    @Override
    public List<Item> getAll() {
        List<Item> all = new ArrayList<>(itemsByUpc.values());
        // Stable default order — by description then UPC — so the Quick Add grid's paging is
        // deterministic before the view applies its own sort.
        all.sort(Comparator.comparing(Item::getDescription, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Item::getUpc));
        return all;
    }

    @Override
    public int size() {
        return itemsByUpc.size();
    }
}
