package com.rocketpartners.onboarding.possystem.repository.h2;

import com.rocketpartners.onboarding.commons.model.Item;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class H2ItemRepositoryTest {

    @Test
    void open_seedsFromClasspath_whenTableEmpty(@TempDir Path tmp) {
        try (H2ItemRepository repo = H2ItemRepository.open(tmp, "test", "/pricebook-happy.tsv")) {
            assertThat(repo.size()).isEqualTo(3);
            Optional<Item> widget = repo.findByUpc("UPC-A");
            assertThat(widget).isPresent();
            assertThat(widget.get().getDescription()).isEqualTo("Widget");
            assertThat(widget.get().getUnitPrice()).isEqualByComparingTo("1.99");
        }
    }

    @Test
    void open_isIdempotent_secondOpenReusesSeededData(@TempDir Path tmp) {
        try (H2ItemRepository first = H2ItemRepository.open(tmp, "test", "/pricebook-happy.tsv")) {
            assertThat(first.size()).isEqualTo(3);
        }
        // Second open — same seed resource, but table is already populated, so the seeder
        // must not re-run (which would violate the PK) and the row count stays the same.
        try (H2ItemRepository second = H2ItemRepository.open(tmp, "test", "/pricebook-happy.tsv")) {
            assertThat(second.size()).isEqualTo(3);
            assertThat(second.findByUpc("UPC-A")).isPresent();
        }
    }

    @Test
    void findByUpc_returnsEmpty_whenAbsent(@TempDir Path tmp) {
        try (H2ItemRepository repo = H2ItemRepository.open(tmp, "test", "/pricebook-happy.tsv")) {
            assertThat(repo.findByUpc("no-such-upc")).isEmpty();
        }
    }

    @Test
    void findByUpc_returnsEmpty_forNullUpc(@TempDir Path tmp) {
        try (H2ItemRepository repo = H2ItemRepository.open(tmp, "test", "/pricebook-happy.tsv")) {
            assertThat(repo.findByUpc(null)).isEmpty();
        }
    }

    @Test
    void findByUpc_roundTripsPriceAtScale2(@TempDir Path tmp) {
        try (H2ItemRepository repo = H2ItemRepository.open(tmp, "test", "/pricebook.tsv")) {
            Optional<Item> polarPop = repo.findByUpc("041594904794");
            assertThat(polarPop).isPresent();
            assertThat(polarPop.get().getDescription()).isEqualTo("CIR K POLAR POP 42OZ FOA");
            assertThat(polarPop.get().getUnitPrice()).isEqualByComparingTo("8.91");
            assertThat(polarPop.get().getUnitPrice().scale()).isEqualTo(2);
        }
    }

    @Test
    void open_loadsRealPricebook(@TempDir Path tmp) {
        try (H2ItemRepository repo = H2ItemRepository.open(tmp, "test", "/pricebook.tsv")) {
            assertThat(repo.size()).isEqualTo(1000);
        }
    }

    @Test
    void open_malformedRow_throwsWithLineNumber(@TempDir Path tmp) {
        assertThatThrownBy(() ->
                H2ItemRepository.open(tmp, "test", "/pricebook-malformed.tsv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("line 2");
    }

    @Test
    void open_missingResource_throws(@TempDir Path tmp) {
        assertThatThrownBy(() ->
                H2ItemRepository.open(tmp, "test", "/no-such.tsv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/no-such.tsv");
    }

    @Test
    void open_nullDbDir_throws() {
        assertThatThrownBy(() ->
                H2ItemRepository.open(null, "test", "/pricebook-happy.tsv"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void open_blankDbName_throws(@TempDir Path tmp) {
        assertThatThrownBy(() ->
                H2ItemRepository.open(tmp, "  ", "/pricebook-happy.tsv"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
