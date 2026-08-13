package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.possystem.component.CloudApiComponent;
import com.rocketpartners.onboarding.possystem.component.EligibilityRule;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.display.CustomerViewControllerTest.RecordingListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.repository.inmemory.InMemoryItemRepository;
import com.rocketpartners.onboarding.possystem.service.DiscountSession;
import com.rocketpartners.onboarding.possystem.service.TaxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DiscountViewControllerTest {

    private static final String GROUP = "CUSTOMER_ELIGIBILITY";
    private static final EligibilityRule SENIOR =
            new EligibilityRule("SENIOR_20", "Senior 20%", DiscountType.PERCENT_OFF, new BigDecimal("20"), GROUP);
    private static final EligibilityRule VETERAN =
            new EligibilityRule("VETERAN_15", "Veteran 15%", DiscountType.PERCENT_OFF, new BigDecimal("15"), GROUP);

    private PosComponent pos;
    private DiscountView view;
    private DiscountSession session;
    private RecordingListener notifications;

    @BeforeEach
    void setUp() {
        pos = new PosComponent(new InMemoryItemRepository(new HashMap<>()),
                new TaxService(BigDecimal.ZERO), "Test", 1, false);
        view = mock(DiscountView.class);
        session = new DiscountSession();
        notifications = new RecordingListener(EnumSet.allOf(PosEventType.class));
        pos.register(notifications);
    }

    /** A CloudApiComponent whose rules fetch returns a canned result. */
    private static CloudApiComponent stubRules(CloudApiComponent.RulesResult result) {
        return new CloudApiComponent("http://localhost:1") {
            @Override
            public RulesResult fetchEligibilityRules() {
                return result;
            }
        };
    }

    private DiscountViewController controllerWith(CloudApiComponent api) {
        // Synchronous executor + EDT runner so the startup fetch completes within start().
        DiscountViewController controller =
                new DiscountViewController(view, api, session, Runnable::run, Runnable::run);
        pos.addController(controller);
        pos.start();
        return controller;
    }

    @Test
    void startupFetch_cachesRules_andJournalsTheOutcome() {
        CloudApiComponent api = stubRules(new CloudApiComponent.RulesResult(List.of(SENIOR, VETERAN), null));
        DiscountViewController controller = controllerWith(api);

        assertThat(controller.getCachedRulesForTest()).extracting(EligibilityRule::code)
                .containsExactly("SENIOR_20", "VETERAN_15");
        PosEvent loaded = notifications.lastOf(PosEventType.DISCOUNT_RULES_LOADED);
        assertThat(loaded.getProperty("ruleCount", Integer.class)).isEqualTo(2);
        assertThat(loaded.getProperty("available", Boolean.class)).isTrue();
    }

    @Test
    void startupFetch_whenEngineUnreachable_reportsUnavailableWithReason() {
        CloudApiComponent api = stubRules(new CloudApiComponent.RulesResult(List.of(), "connection refused"));
        DiscountViewController controller = controllerWith(api);

        assertThat(controller.getCachedRulesForTest()).isEmpty();
        PosEvent loaded = notifications.lastOf(PosEventType.DISCOUNT_RULES_LOADED);
        assertThat(loaded.getProperty("available", Boolean.class)).isFalse();
        assertThat(loaded.getProperty("reason", String.class)).isEqualTo("connection refused");
    }

    @Test
    void confirm_recordsSelectionIntoSession_andJournalsIt() {
        CloudApiComponent api = stubRules(new CloudApiComponent.RulesResult(List.of(SENIOR, VETERAN), null));
        controllerWith(api);

        pos.dispatchPosEvent(confirm("SENIOR_20"));
        assertThat(session.getSelectedCodes()).containsExactly("SENIOR_20");
        PosEvent applied = notifications.lastOf(PosEventType.ELIGIBILITY_DISCOUNT_SELECTED);
        assertThat(applied.getProperty("code", String.class)).isEqualTo("SENIOR_20");
        assertThat(applied.getProperty("idVerified", Boolean.class)).isTrue();
    }

    @Test
    void confirm_secondSameGroupSelection_reportsReplacedCode() {
        CloudApiComponent api = stubRules(new CloudApiComponent.RulesResult(List.of(SENIOR, VETERAN), null));
        controllerWith(api);

        pos.dispatchPosEvent(confirm("SENIOR_20"));
        pos.dispatchPosEvent(confirm("VETERAN_15"));

        assertThat(session.getSelectedCodes()).containsExactly("VETERAN_15");
        PosEvent applied = notifications.lastOf(PosEventType.ELIGIBILITY_DISCOUNT_SELECTED);
        assertThat(applied.getProperty("code", String.class)).isEqualTo("VETERAN_15");
        assertThat(applied.getProperty("replaced", String.class)).isEqualTo("SENIOR_20");
    }

    private static PosEvent confirm(String code) {
        Map<String, Object> props = new HashMap<>();
        props.put("code", code);
        props.put("idVerified", Boolean.TRUE);
        return new PosEvent(PosEventType.DISCOUNT_CONFIRM_PRESSED, props);
    }
}
