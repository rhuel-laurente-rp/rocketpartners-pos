package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.possystem.component.EligibilityRule;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class DiscountViewTest {

    private static final String GROUP = "CUSTOMER_ELIGIBILITY";
    private static final EligibilityRule SENIOR =
            new EligibilityRule("SENIOR_20", "Senior 20%", DiscountType.PERCENT_OFF, new BigDecimal("20"), GROUP);
    private static final EligibilityRule VETERAN =
            new EligibilityRule("VETERAN_15", "Veteran 15%", DiscountType.PERCENT_OFF, new BigDecimal("15"), GROUP);

    private final List<PosEvent> dispatched = new ArrayList<>();
    private final IPosEventDispatcher recorder = dispatched::add;

    @Test
    void idVerifiedUnchecked_blocksConfirm_checkedWithSelectionEnablesIt() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        DiscountView view = new DiscountView(null, recorder);
        try {
            view.prepareForTest(List.of(SENIOR, VETERAN), List.of());
            assertThat(view.getConfirmButtonForTest().isEnabled()).isFalse();

            // Selecting a rule alone is not enough — ID must be verified first.
            view.clickRuleForTest("SENIOR_20");
            assertThat(view.getConfirmButtonForTest().isEnabled()).isFalse();

            view.getIdVerifiedForTest().doClick();
            assertThat(view.getConfirmButtonForTest().isEnabled()).isTrue();

            // Unchecking ID re-blocks confirm.
            view.getIdVerifiedForTest().doClick();
            assertThat(view.getConfirmButtonForTest().isEnabled()).isFalse();
        } finally {
            view.dispose();
        }
    }

    @Test
    void selectingSecondSameGroupRule_deselectsFirst() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        DiscountView view = new DiscountView(null, recorder);
        try {
            view.prepareForTest(List.of(SENIOR, VETERAN), List.of());
            view.clickRuleForTest("SENIOR_20");
            view.clickRuleForTest("VETERAN_15");
            assertThat(view.isRuleSelectedForTest("SENIOR_20")).isFalse();
            assertThat(view.isRuleSelectedForTest("VETERAN_15")).isTrue();
        } finally {
            view.dispose();
        }
    }

    @Test
    void confirm_dispatchesSelectionWithIdVerifiedFlag() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        DiscountView view = new DiscountView(null, recorder);
        try {
            view.prepareForTest(List.of(SENIOR, VETERAN), List.of());
            view.clickRuleForTest("SENIOR_20");
            view.getIdVerifiedForTest().doClick();
            dispatched.clear();
            view.getConfirmButtonForTest().doClick();

            PosEvent event = dispatched.stream()
                    .filter(e -> e.getType() == PosEventType.DISCOUNT_CONFIRM_PRESSED)
                    .findFirst().orElseThrow();
            assertThat(event.getProperty("code", String.class)).isEqualTo("SENIOR_20");
            assertThat(event.getProperty("idVerified", Boolean.class)).isTrue();
        } finally {
            view.dispose();
        }
    }

    @Test
    void idVerifiedLabel_ridesOnTheCheckbox_soTappingTheTextTogglesIt() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        DiscountView view = new DiscountView(null, recorder);
        try {
            javax.swing.JCheckBox box = view.getIdVerifiedForTest();
            // The label is the checkbox's own text (not a separate JLabel beside it), so the whole
            // control — glyph and label — is one hit area: tapping the text toggles the same box.
            assertThat(box.getText()).isEqualTo("ID Verified");
            assertThat(box.isSelected()).isFalse();
            box.doClick();
            assertThat(box.isSelected()).isTrue();
        } finally {
            view.dispose();
        }
    }

    @Test
    void emptyRules_confirmStaysDisabled() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        DiscountView view = new DiscountView(null, recorder);
        try {
            view.prepareForTest(List.of(), List.of());
            assertThat(view.ruleCodesForTest()).isEmpty();
            // Even checking ID can't enable confirm with nothing to apply.
            view.getIdVerifiedForTest().doClick();
            assertThat(view.getConfirmButtonForTest().isEnabled()).isFalse();
        } finally {
            view.dispose();
        }
    }
}
