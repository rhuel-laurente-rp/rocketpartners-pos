package com.rocketpartners.onboarding.possystem.repository;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.possystem.repository.inmemory.InMemoryItemRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpcResolverTest {

    private static InMemoryItemRepository repoOf(Item... items) {
        Map<String, Item> map = new LinkedHashMap<>();
        for (Item i : items) map.put(i.getUpc(), i);
        return new InMemoryItemRepository(map);
    }

    @Test
    void exactMatchWins_forFullTwelveDigitCode() {
        Item coke = new Item("049000053418", "COKE", new BigDecimal("1.99"));
        InMemoryItemRepository repo = repoOf(coke);

        Optional<UpcResolver.Resolution> resolved = UpcResolver.resolve(repo, "049000053418");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getItem()).isEqualTo(coke);
        assertThat(resolved.get().getRung()).isEqualTo(UpcResolver.Rung.EXACT);
        assertThat(resolved.get().getMatchedKey()).isEqualTo("049000053418");
    }

    @Test
    void zeroPaddedTwelveDigitInput_resolvesShortPricebookCode_viaStripping() {
        // Pricebook holds "1234"; scanner emits "000000001234" (12-digit UPC-A form).
        Item stockItem = new Item("1234", "Loto Valideuse", new BigDecimal("4.00"));
        InMemoryItemRepository repo = repoOf(stockItem);

        Optional<UpcResolver.Resolution> resolved = UpcResolver.resolve(repo, "000000001234");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getItem()).isEqualTo(stockItem);
        assertThat(resolved.get().getRung()).isEqualTo(UpcResolver.Rung.STRIPPED_LEADING_ZEROS);
        assertThat(resolved.get().getMatchedKey()).isEqualTo("1234");
    }

    @Test
    void thirteenDigitLeadingZero_form_resolvesToUpcAEntry() {
        // Some scanners emit UPC-A as EAN-13 with a leading zero. The ladder strips
        // progressively — dropping just the outermost zero matches the 12-digit pricebook
        // form directly, before the fully-stripped 11-digit form is tried.
        Item coke = new Item("049000053418", "COKE", new BigDecimal("1.99"));
        InMemoryItemRepository repo = repoOf(coke);

        Optional<UpcResolver.Resolution> resolved = UpcResolver.resolve(repo, "0049000053418");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getItem()).isEqualTo(coke);
        assertThat(resolved.get().getRung()).isEqualTo(UpcResolver.Rung.STRIPPED_LEADING_ZEROS);
        assertThat(resolved.get().getMatchedKey()).isEqualTo("049000053418");
    }

    @Test
    void checkDigitDropped_appliesOnlyWhenChecksumValid() {
        // 12-digit input 049000053418 has a valid check digit. Rung 3 drops the '8' →
        // "04900005341", then strips leading zeros → "4900005341". Set that up as a key.
        Item payloadItem = new Item("4900005341", "Fictional Payload", new BigDecimal("1.00"));
        InMemoryItemRepository repo = repoOf(payloadItem);

        Optional<UpcResolver.Resolution> resolved = UpcResolver.resolve(repo, "049000053418");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getRung()).isEqualTo(UpcResolver.Rung.DROP_CHECK_DIGIT);
        assertThat(resolved.get().getMatchedKey()).isEqualTo("4900005341");
    }

    @Test
    void checkDigitDropped_isSkipped_whenChecksumInvalid() {
        // Same fictional payload item, but the input has a BAD check digit — rung 3 should not
        // fire, because we do not treat the last digit of a bad-checksum code as a checksum.
        Item payloadItem = new Item("4900005341", "Fictional Payload", new BigDecimal("1.00"));
        InMemoryItemRepository repo = repoOf(payloadItem);

        Optional<UpcResolver.Resolution> resolved = UpcResolver.resolve(repo, "049000053417");

        assertThat(resolved).isEmpty();
    }

    @Test
    void exactMatchBeatsStripping_whenBothWouldResolve() {
        // If both "000123" and "123" were in the pricebook (they shouldn't — the collision
        // guard fails the build in that case), EXACT wins the tie.
        Item exact = new Item("000123", "Exact", new BigDecimal("1.00"));
        InMemoryItemRepository repo = repoOf(exact);

        Optional<UpcResolver.Resolution> resolved = UpcResolver.resolve(repo, "000123");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getRung()).isEqualTo(UpcResolver.Rung.EXACT);
    }

    @Test
    void nullOrEmptyInput_returnsEmpty() {
        InMemoryItemRepository repo = repoOf(
                new Item("123", "X", new BigDecimal("1.00")));
        assertThat(UpcResolver.resolve(repo, null)).isEmpty();
        assertThat(UpcResolver.resolve(repo, "")).isEmpty();
    }

    @Test
    void unknownCode_returnsEmpty() {
        InMemoryItemRepository repo = repoOf(
                new Item("049000053418", "COKE", new BigDecimal("1.99")));
        assertThat(UpcResolver.resolve(repo, "999999999999")).isEmpty();
    }

    @Test
    void nullRepository_throws() {
        assertThatThrownBy(() -> UpcResolver.resolve(null, "1234"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stripLeadingZeros_utility() {
        assertThat(UpcResolver.stripLeadingZeros("000000001234")).isEqualTo("1234");
        assertThat(UpcResolver.stripLeadingZeros("1234")).isEqualTo("1234");
        assertThat(UpcResolver.stripLeadingZeros("0000")).isEmpty();
        assertThat(UpcResolver.stripLeadingZeros("")).isEmpty();
    }
}
