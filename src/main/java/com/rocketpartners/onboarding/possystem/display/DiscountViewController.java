package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.component.CloudApiComponent;
import com.rocketpartners.onboarding.possystem.component.EligibilityRule;
import com.rocketpartners.onboarding.possystem.component.IController;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.service.DiscountSession;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Owns the eligibility-discount flow: the one-time startup fetch of the rules the cashier can
 * apply, the modal {@link DiscountView}, and recording the cashier's choice into the
 * transaction-scoped {@link DiscountSession}.
 *
 * <p><strong>Startup fetch, off the EDT.</strong> On {@link #onStart(PosComponent)} the controller
 * asks the engine for the active {@code ELIGIBILITY} rules and caches them. The fetch runs on a
 * background thread so a slow or unreachable engine cannot stall the boot; the result (and a
 * {@link PosEventType#DISCOUNT_RULES_LOADED} journal event) is delivered back on the EDT. If the
 * engine was unreachable the cache is empty and the dialog says so — sales continue regardless.</p>
 *
 * <p><strong>Selection, not calculation.</strong> This controller never computes a discount. It
 * records which eligibility codes the cashier picked (into {@link DiscountSession}, which enforces
 * one-per-exclusivity-group) and dispatches {@link PosEventType#ELIGIBILITY_DISCOUNT_SELECTED} so
 * the running preview refreshes and the journal captures the code, ID-verification flag, operator,
 * and any replaced rule. The authoritative discount figures come from the engine at Total, driven
 * by {@link CustomerViewController}.</p>
 */
public class DiscountViewController implements IController, IPosEventListener {

    /** Marshals a task onto the EDT. Default: {@link SwingUtilities#invokeLater}. */
    @FunctionalInterface
    public interface EdtRunner {
        void run(Runnable r);
    }

    private static final Set<PosEventType> LISTEN_TYPES = Collections.unmodifiableSet(EnumSet.of(
            PosEventType.DISCOUNT_PRESSED,
            PosEventType.DISCOUNT_CONFIRM_PRESSED));

    private final DiscountView view;
    private final CloudApiComponent cloudApi;
    private final DiscountSession session;
    private final Executor fetchExecutor;
    private final EdtRunner edtRunner;

    /** Cached eligibility rules from the startup fetch; empty until the fetch completes (or if it failed). */
    private final List<EligibilityRule> cachedRules = new ArrayList<>();

    private PosComponent parent;

    /**
     * Production constructor: fetch on a daemon background thread, deliver on the EDT.
     *
     * @param view     the modal dialog this controller drives; must not be {@code null}
     * @param cloudApi the discount-engine client; must not be {@code null}
     * @param session  the transaction-scoped eligibility selection; must not be {@code null}
     */
    public DiscountViewController(DiscountView view, CloudApiComponent cloudApi, DiscountSession session) {
        this(view, cloudApi, session,
                r -> {
                    Thread t = new Thread(r, "discount-rules-fetch");
                    t.setDaemon(true);
                    t.start();
                },
                SwingUtilities::invokeLater);
    }

    /** Test-facing constructor: inject a synchronous executor and EDT runner. */
    DiscountViewController(DiscountView view, CloudApiComponent cloudApi, DiscountSession session,
                           Executor fetchExecutor, EdtRunner edtRunner) {
        if (view == null) throw new IllegalArgumentException("view must not be null");
        if (cloudApi == null) throw new IllegalArgumentException("cloudApi must not be null");
        if (session == null) throw new IllegalArgumentException("session must not be null");
        if (fetchExecutor == null) throw new IllegalArgumentException("fetchExecutor must not be null");
        if (edtRunner == null) throw new IllegalArgumentException("edtRunner must not be null");
        this.view = view;
        this.cloudApi = cloudApi;
        this.session = session;
        this.fetchExecutor = fetchExecutor;
        this.edtRunner = edtRunner;
    }

    // ---- IController ------------------------------------------------------

    @Override
    public void onStart(PosComponent parent) {
        this.parent = parent;
        parent.register(this);
        fetchRules();
    }

    @Override
    public void onEnd() {
        if (parent != null) {
            parent.unregister(this);
            parent = null;
        }
        view.closeDialog();
    }

    // ---- IPosEventListener ------------------------------------------------

    @Override
    public Set<PosEventType> getListeningEventTypes() {
        return LISTEN_TYPES;
    }

    @Override
    public void onPosEvent(PosEvent event) {
        switch (event.getType()) {
            case DISCOUNT_PRESSED -> openDialog();
            case DISCOUNT_CONFIRM_PRESSED -> recordSelection(event);
            default -> { /* not subscribed */ }
        }
    }

    // ---- Startup fetch ----------------------------------------------------

    private void fetchRules() {
        fetchExecutor.execute(() -> {
            // Eligibility rules drive the cashier dialog; promotional rules populate CloudApiComponent's
            // cache so an applied promo at Total can be mapped back to its basket line for the FREE tag.
            CloudApiComponent.RulesResult result = cloudApi.fetchEligibilityRules();
            cloudApi.fetchPromotionalRules();
            edtRunner.run(() -> onRulesFetched(result));
        });
    }

    private void onRulesFetched(CloudApiComponent.RulesResult result) {
        cachedRules.clear();
        cachedRules.addAll(result.rules());
        if (parent == null) return; // controller was ended before the fetch returned
        Map<String, Object> props = new HashMap<>();
        props.put("ruleCount", cachedRules.size());
        props.put("available", result.ok());
        if (!result.ok()) props.put("reason", result.error());
        parent.dispatchPosEvent(new PosEvent(PosEventType.DISCOUNT_RULES_LOADED, props));
    }

    // ---- Dialog -----------------------------------------------------------

    private void openDialog() {
        view.openFor(new ArrayList<>(cachedRules), session.getSelectedCodes());
    }

    private void recordSelection(PosEvent event) {
        String code = event.getProperty("code", String.class);
        if (code == null) return;
        EligibilityRule rule = ruleByCode(code);
        if (rule == null) return; // unknown code (stale cache) — ignore rather than apply a phantom
        String replaced = session.select(rule);

        Map<String, Object> props = new HashMap<>();
        props.put("code", rule.code());
        props.put("description", rule.description());
        props.put("idVerified", event.getProperty("idVerified", Boolean.class, Boolean.FALSE));
        if (replaced != null) props.put("replaced", replaced);
        // Dispatched AFTER the session is updated so CustomerViewController's re-render sees the new
        // selection, and JournalListener records the code, ID flag, operator, and any replaced rule.
        parent.dispatchPosEvent(new PosEvent(PosEventType.ELIGIBILITY_DISCOUNT_SELECTED, props));
    }

    private EligibilityRule ruleByCode(String code) {
        for (EligibilityRule r : cachedRules) {
            if (r.code().equals(code)) return r;
        }
        return null;
    }

    // ---- Test hooks -------------------------------------------------------

    List<EligibilityRule> getCachedRulesForTest() {
        return new ArrayList<>(cachedRules);
    }
}
