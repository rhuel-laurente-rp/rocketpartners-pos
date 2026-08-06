package com.rocketpartners.onboarding.possystem.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxServiceTest {

    @Test
    void getRate_returnsInjectedRate() {
        TaxService svc = new TaxService(new BigDecimal("0.07"));
        assertThat(svc.getRate()).isEqualByComparingTo("0.07");
    }

    @Test
    void constructor_rejectsNullRate() {
        assertThatThrownBy(() -> new TaxService(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNegativeRate() {
        assertThatThrownBy(() -> new TaxService(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_acceptsZeroRate() {
        TaxService svc = new TaxService(BigDecimal.ZERO);
        assertThat(svc.getRate()).isEqualByComparingTo("0");
    }
}
