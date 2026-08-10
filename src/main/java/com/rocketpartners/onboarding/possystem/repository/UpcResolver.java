package com.rocketpartners.onboarding.possystem.repository;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.possystem.component.Barcodes;

import java.util.Optional;

/**
 * Ordered normalisation ladder for resolving a scanned barcode against an {@link ItemRepository}.
 *
 * <p>Reality check: our pricebook keys items on codes of assorted lengths (2, 3, 4, 5, 6, and 12
 * digits in the sample data), but the hardware scanner always emits 12 digits — a short code
 * printed as a UPC-A barcode is zero-padded up to 12. A single exact-match lookup would find
 * every full-length UPC in the pricebook and miss every short code. The ladder is what makes
 * the scan path tolerant of that mismatch WITHOUT rewriting the pricebook.</p>
 *
 * <p>Three ordered rungs, first hit wins:</p>
 * <ol>
 *   <li>{@link Rung#EXACT} — the received digits, as-is. Wins for full 12-digit UPCs already
 *       stored in the pricebook (the majority case).</li>
 *   <li>{@link Rung#STRIPPED_LEADING_ZEROS} — leading zeros removed. Covers a short pricebook
 *       code printed as a zero-padded UPC-A (device emits {@code 000000001234}, pricebook holds
 *       {@code 1234}) and also absorbs the leading-zero EAN-13 encoding many scanners use for
 *       UPC-A ({@code 0049000053418} → {@code 49000053418} → …).</li>
 *   <li>{@link Rung#DROP_CHECK_DIGIT} — for 12-digit input with a valid UPC-A check digit only,
 *       drop the check digit and then strip leading zeros. Covers a pricebook that stores the
 *       payload without its trailing checksum. Only applied when the check digit is valid so we
 *       do not accidentally match a nearby but wrong item.</li>
 * </ol>
 *
 * <p>The ladder is intentionally short and ordered. No fuzzy matching, no substring search, no
 * prefix index — a lookup that "helpfully" finds a nearby code is how the wrong item gets rung
 * up.</p>
 *
 * <p>The resolver has no state; the two static methods are the whole surface. Callers pass an
 * {@link ItemRepository} in per call.</p>
 */
public final class UpcResolver {

    /** Which rung of the ladder produced the hit. */
    public enum Rung {
        /** Exact-match lookup on the digits as received. */
        EXACT,
        /** Leading zeros stripped from the input. */
        STRIPPED_LEADING_ZEROS,
        /** UPC-A check digit dropped, then leading zeros stripped. */
        DROP_CHECK_DIGIT
    }

    /** Successful resolution: which rung matched, and the normalised key that hit. */
    public static final class Resolution {
        private final Item item;
        private final Rung rung;
        private final String matchedKey;

        Resolution(Item item, Rung rung, String matchedKey) {
            this.item = item;
            this.rung = rung;
            this.matchedKey = matchedKey;
        }

        public Item getItem() { return item; }
        public Rung getRung() { return rung; }
        public String getMatchedKey() { return matchedKey; }
    }

    private UpcResolver() {}

    /**
     * Walks the ladder and returns the first hit, or empty if none of the rungs resolve.
     *
     * @param repository the pricebook; must not be {@code null}
     * @param raw        the raw scanned digits (validated by {@link Barcodes#isValidUpc}
     *                   upstream); {@code null} or empty returns empty
     */
    public static Optional<Resolution> resolve(ItemRepository repository, String raw) {
        if (repository == null) throw new IllegalArgumentException("repository must not be null");
        if (raw == null || raw.isEmpty()) return Optional.empty();

        // Rung 1: exact.
        Optional<Item> exact = repository.findByUpc(raw);
        if (exact.isPresent()) {
            return Optional.of(new Resolution(exact.get(), Rung.EXACT, raw));
        }

        // Rung 2: leading zeros stripped. Only attempt when input actually starts with '0' —
        // otherwise it would be the same lookup as rung 1. We progressively strip one zero at
        // a time: the pricebook may itself hold codes with leading zeros (e.g. "049000053418"),
        // so a 13-digit EAN-13-encoded scan "0049000053418" must be able to resolve to the
        // 12-digit pricebook key by dropping just the outermost zero. Iterating stops when we
        // hit either a match, a non-zero prefix, or an empty string.
        if (raw.charAt(0) == '0') {
            for (int i = 1; i <= raw.length(); i++) {
                if (i < raw.length() && raw.charAt(i - 1) != '0') break; // only strip zeros
                String candidate = raw.substring(i);
                if (candidate.isEmpty() || candidate.equals(raw)) break;
                Optional<Item> hit = repository.findByUpc(candidate);
                if (hit.isPresent()) {
                    return Optional.of(new Resolution(hit.get(), Rung.STRIPPED_LEADING_ZEROS, candidate));
                }
            }
        }

        // Rung 3: 12-digit input with a valid UPC-A check digit. Drop the last digit, then
        // strip leading zeros from the 11-digit payload — again progressively, for the same
        // reason as rung 2. Precondition — valid check digit — gates this rung so we do not
        // treat the last digit of a bad-checksum code as a checksum.
        if (raw.length() == Barcodes.UPC_A_LENGTH && Barcodes.hasValidUpcACheckDigit(raw)) {
            String payload = raw.substring(0, Barcodes.UPC_A_LENGTH - 1);
            for (int i = 0; i <= payload.length(); i++) {
                if (i > 0 && i - 1 < payload.length() && payload.charAt(i - 1) != '0') break;
                String key = payload.substring(i);
                if (key.isEmpty() || key.equals(raw)) continue;
                Optional<Item> hit = repository.findByUpc(key);
                if (hit.isPresent()) {
                    return Optional.of(new Resolution(hit.get(), Rung.DROP_CHECK_DIGIT, key));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * @return {@code s} with any leading {@code '0'} characters removed; an all-zero string
     *         collapses to the empty string
     */
    static String stripLeadingZeros(String s) {
        int i = 0;
        int len = s.length();
        while (i < len && s.charAt(i) == '0') i++;
        return s.substring(i);
    }
}
