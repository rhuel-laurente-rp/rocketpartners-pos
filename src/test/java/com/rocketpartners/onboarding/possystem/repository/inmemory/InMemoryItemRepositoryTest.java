package com.rocketpartners.onboarding.possystem.repository.inmemory;

import com.rocketpartners.onboarding.commons.model.Item;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryItemRepositoryTest {

    private static InMemoryItemRepository happy() {
        return InMemoryItemRepository.loadFromClasspath("/pricebook-happy.tsv");
    }

    @Test
    void findByUpc_returnsItem_whenPresent() {
        Optional<Item> item = happy().findByUpc("UPC-A");
        assertThat(item).isPresent();
        assertThat(item.get().getUpc()).isEqualTo("UPC-A");
        assertThat(item.get().getDescription()).isEqualTo("Widget");
        assertThat(item.get().getUnitPrice()).isEqualByComparingTo("1.99");
    }

    @Test
    void findByUpc_returnsEmpty_whenAbsent() {
        assertThat(happy().findByUpc("no-such-upc")).isEmpty();
    }

    @Test
    void findByUpc_returnsEmpty_forNullUpc() {
        assertThat(happy().findByUpc(null)).isEmpty();
    }

    @Test
    void size_reflectsLoadedRowCount() {
        assertThat(happy().size()).isEqualTo(3);
    }

    @Test
    void loadFromClasspath_malformedRow_throwsWithLineNumber() {
        assertThatThrownBy(() -> InMemoryItemRepository.loadFromClasspath("/pricebook-malformed.tsv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("line 2");
    }

    @Test
    void loadFromClasspath_missingResource_throws() {
        assertThatThrownBy(() -> InMemoryItemRepository.loadFromClasspath("/no-such.tsv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/no-such.tsv");
    }

    @Test
    void loadFromClasspath_loadsRealPricebook() {
        InMemoryItemRepository repo = InMemoryItemRepository.loadFromClasspath("/pricebook.tsv");
        assertThat(repo.size()).isEqualTo(1000);
        Optional<Item> polarPop = repo.findByUpc("041594904794");
        assertThat(polarPop).isPresent();
        assertThat(polarPop.get().getDescription()).isEqualTo("CIR K POLAR POP 42OZ FOA");
        assertThat(polarPop.get().getUnitPrice()).isEqualByComparingTo("8.91");
    }
}
