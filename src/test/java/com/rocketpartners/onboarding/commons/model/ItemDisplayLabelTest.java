package com.rocketpartners.onboarding.commons.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * getDisplayLabel() should fall back to description whenever the display name is missing.
 * The 3-arg constructor is the common path today, so it must produce a working label; the
 * 4-arg constructor is the future path for a friendlier customer-facing label.
 */
class ItemDisplayLabelTest {

    @Test
    void displayLabel_fallsBackToDescription_whenDisplayNameOmitted() {
        Item raw = new Item("UPC-1", "RED BULL ENERGY DRIN", new BigDecimal("3.79"));
        assertThat(raw.getDisplayLabel()).isEqualTo("RED BULL ENERGY DRIN");
    }

    @Test
    void displayLabel_fallsBackToDescription_whenDisplayNameNull() {
        Item raw = new Item("UPC-1", "RED BULL ENERGY DRIN", new BigDecimal("3.79"), null);
        assertThat(raw.getDisplayLabel()).isEqualTo("RED BULL ENERGY DRIN");
    }

    @Test
    void displayLabel_fallsBackToDescription_whenDisplayNameBlank() {
        Item raw = new Item("UPC-1", "RED BULL ENERGY DRIN", new BigDecimal("3.79"), "  ");
        assertThat(raw.getDisplayLabel()).isEqualTo("RED BULL ENERGY DRIN");
    }

    @Test
    void displayLabel_prefersFriendlyName_whenPresent() {
        Item raw = new Item("UPC-1", "RED BULL ENERGY DRIN", new BigDecimal("3.79"), "Red Bull");
        assertThat(raw.getDisplayLabel()).isEqualTo("Red Bull");
    }
}
