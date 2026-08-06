package com.rocketpartners.onboarding.possystem.repository;

import com.rocketpartners.onboarding.commons.model.Item;

import java.util.Optional;

/**
 * The UPC → {@link Item} store the POS looks items up in on scan or Quick Add.
 *
 * <p>Concrete implementations decide how items are loaded (from a TSV pricebook, from H2, from
 * a REST service) and how they are held (in memory, on disk). Callers — primarily
 * {@code TransactionService} — depend only on this interface.</p>
 */
public interface ItemRepository {

    /**
     * Looks up an item by its UPC.
     *
     * @param upc the barcode; may be {@code null} (returns {@link Optional#empty()} in that case)
     * @return the item, or {@link Optional#empty()} if no item matches
     */
    Optional<Item> findByUpc(String upc);

    /**
     * @return the number of items currently in the repository
     */
    int size();
}
