package com.rocketpartners.onboarding.commons.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LineItemTest {

    private static Item item(String price) {
        return new Item("UPC-1", "Widget", new BigDecimal(price));
    }

    @Test
    void extendedTotal_singleUnit_matchesUnitPrice() {
        LineItem line = new LineItem(item("1.99"), 1);
        assertThat(line.extendedTotal()).isEqualByComparingTo("1.99");
    }

    @Test
    void extendedTotal_multipleUnits_scalesLinearly() {
        LineItem line = new LineItem(item("1.99"), 3);
        assertThat(line.extendedTotal()).isEqualByComparingTo("5.97");
    }

    @Test
    void extendedTotal_voidedLine_isZero() {
        LineItem line = new LineItem(item("1.99"), 3);
        line.setVoided(true);
        assertThat(line.extendedTotal()).isEqualByComparingTo("0");
    }

    @Test
    void constructor_rejectsZeroQuantity() {
        assertThatThrownBy(() -> new LineItem(item("1.00"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNegativeQuantity() {
        assertThatThrownBy(() -> new LineItem(item("1.00"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNullItem() {
        assertThatThrownBy(() -> new LineItem(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
