package com.rocketpartners.onboarding.possystem.service;

import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.possystem.component.EligibilityRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DiscountSessionTest {

    private static final String GROUP = "CUSTOMER_ELIGIBILITY";
    private static final EligibilityRule SENIOR =
            new EligibilityRule("SENIOR_20", "Senior 20%", DiscountType.PERCENT_OFF, new BigDecimal("20"), GROUP);
    private static final EligibilityRule VETERAN =
            new EligibilityRule("VETERAN_15", "Veteran 15%", DiscountType.PERCENT_OFF, new BigDecimal("15"), GROUP);

    @Test
    void selectingSecondSameGroupDiscount_replacesTheFirst() {
        DiscountSession session = new DiscountSession();
        assertThat(session.select(SENIOR)).isNull();
        assertThat(session.getSelectedCodes()).containsExactly("SENIOR_20");

        // A second discount in the same exclusivity group replaces the first — never stacks. Without
        // this, discountTotal() (which sums with no dedup) would let a cashier stack 20% + 15%.
        String replaced = session.select(VETERAN);
        assertThat(replaced).isEqualTo("SENIOR_20");
        assertThat(session.getSelectedCodes()).containsExactly("VETERAN_15");
    }

    @Test
    void reselectingSameCode_isNoReplacement_andNoDuplicate() {
        DiscountSession session = new DiscountSession();
        session.select(SENIOR);
        assertThat(session.select(SENIOR)).isNull();
        assertThat(session.getSelectedCodes()).containsExactly("SENIOR_20");
    }

    @Test
    void differentGroups_stackFreely() {
        DiscountSession session = new DiscountSession();
        EligibilityRule other =
                new EligibilityRule("STAFF", "Staff", DiscountType.PERCENT_OFF, new BigDecimal("5"), "STAFF_GROUP");
        session.select(SENIOR);
        assertThat(session.select(other)).isNull();
        assertThat(session.getSelectedCodes()).containsExactly("SENIOR_20", "STAFF");
    }

    @Test
    void clear_emptiesTheSelection() {
        DiscountSession session = new DiscountSession();
        session.select(SENIOR);
        session.clear();
        assertThat(session.isEmpty()).isTrue();
        assertThat(session.getSelectedCodes()).isEmpty();
    }
}
