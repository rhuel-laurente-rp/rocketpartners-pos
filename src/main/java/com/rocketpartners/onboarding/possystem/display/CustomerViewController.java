package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.dto.LineItemDto;
import com.rocketpartners.onboarding.commons.dto.TransactionDto;
import com.rocketpartners.onboarding.commons.model.Discount;
import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.commons.model.TransactionState;
import com.rocketpartners.onboarding.possystem.component.CloudApiComponent;
import com.rocketpartners.onboarding.possystem.component.EligibilityRule;
import com.rocketpartners.onboarding.possystem.component.IController;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.service.DiscountPreview;
import com.rocketpartners.onboarding.possystem.service.DiscountSession;
import com.rocketpartners.onboarding.possystem.service.TransactionService;

import javax.swing.SwingUtilities;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Handles user input from {@link CustomerView} and mirrors {@link TransactionService} state back
 * to it. No Swing rendering: the view paints, the controller decides.
 *
 * <p>Subscribes to the three basket-input event types ({@link PosEventType#QUICK_ADD_PRESSED},
 * {@link PosEventType#VOID_LINE_PRESSED}, {@link PosEventType#TOTAL_PRESSED}) plus the
 * confirmed void-basket event ({@link PosEventType#VOID_BASKET_CONFIRM_PRESSED}) and
 * {@link PosEventType#RECEIPT_DISMISSED} as a lifecycle signal. The three tender-input events
 * belong to child controllers ({@link PayWithCashViewController},
 * {@link PayWithCardViewController}); this controller doesn't tender itself. The
 * initial-press event {@link PosEventType#VOID_BASKET_PRESSED} belongs to
 * {@link VoidBasketConfirmViewController}, which opens the confirmation dialog. Voiding is
 * only committed once the cashier confirms — this controller reacts to that second event.</p>
 *
 * <p>After any terminal transition — {@link PosEventType#VOID_BASKET_CONFIRM_PRESSED} or a
 * tender followed by receipt dismissal (surfaced via {@link PosEventType#RECEIPT_DISMISSED}) —
 * the controller opens a fresh transaction so the next customer can be rung up without a
 * restart. Waiting for {@code RECEIPT_DISMISSED} rather than {@code TRANSACTION_COMPLETED}
 * ensures the cashier sees the receipt before the display flips back to an empty basket.</p>
 *
 * <p>Service calls that throw are swallowed at this layer — the service has already dispatched
 * an {@link PosEventType#ERROR} event and the view has not yet been updated for the failed
 * operation, so the display stays consistent with the transaction. The controller does not
 * re-throw, so a bad UPC or an illegal state cannot kill the Swing event loop.</p>
 */
public class CustomerViewController implements IController, IPosEventListener {

    private static final Set<PosEventType> LISTEN_TYPES = Collections.unmodifiableSet(EnumSet.of(
            PosEventType.QUICK_ADD_PRESSED,
            PosEventType.VOID_LINE_PRESSED,
            // VOID_BASKET_PRESSED belongs to VoidBasketConfirmViewController — that controller
            // opens the confirmation dialog. This controller waits for the second, confirmed
            // event before committing the void.
            PosEventType.VOID_BASKET_CONFIRM_PRESSED,
            PosEventType.TOTAL_PRESSED,
            PosEventType.ITEM_SCANNED,
            // Re-render whenever a peer controller has mutated the basket (e.g. the
            // change-qty dialog changed a quantity or voided a line via the zero path) or the
            // eligibility selection changed (so the running preview updates).
            PosEventType.QUANTITY_CHANGED,
            PosEventType.LINE_VOIDED,
            PosEventType.ELIGIBILITY_DISCOUNT_SELECTED,
            PosEventType.RECEIPT_DISMISSED));

    /**
     * Runs the discount-engine call off the EDT and delivers the result back on the EDT. A
     * two-second timeout executed inline on {@code TOTAL_PRESSED} (which arrives on the EDT) would
     * freeze the whole UI — including the repaint that shows "Calculating Discounts". The default
     * runs the work on a daemon thread and marshals the callback via
     * {@link SwingUtilities#invokeLater}. Tests inject a synchronous variant.
     */
    @FunctionalInterface
    public interface DiscountCalcScheduler {
        void schedule(Supplier<CloudApiComponent.CalculateResult> work,
                      Consumer<CloudApiComponent.CalculateResult> onResult);
    }

    private final CustomerView view;
    private final CloudApiComponent cloudApi;
    private final DiscountSession discountSession;
    private final DiscountCalcScheduler discountScheduler;
    private PosComponent parent;

    /**
     * Phase-1 constructor: no discount engine wired. Total enables tender immediately and the
     * summary shows no discount. Retained so tests and any engine-less boot behave exactly as
     * before this feature landed.
     *
     * @param view the view this controller drives; must not be {@code null}
     */
    public CustomerViewController(CustomerView view) {
        this(view, null, new DiscountSession(), defaultScheduler());
    }

    /**
     * Wires the discount engine and the eligibility selection with the default off-EDT scheduler
     * (a daemon thread; callback marshalled via {@link SwingUtilities#invokeLater}). This is the
     * production wiring used by {@code Application}.
     *
     * @param view     the view this controller drives; must not be {@code null}
     * @param cloudApi the discount-engine client; must not be {@code null}
     * @param session  the eligibility-discount selection for the current transaction; must not be
     *                 {@code null}
     */
    public CustomerViewController(CustomerView view, CloudApiComponent cloudApi, DiscountSession session) {
        this(view, cloudApi, session, defaultScheduler());
    }

    /**
     * Full constructor: wires the discount engine, the transaction-scoped eligibility selection,
     * and the off-EDT scheduler used at Total.
     *
     * @param view      the view this controller drives; must not be {@code null}
     * @param cloudApi  the discount-engine client; {@code null} disables the engine call at Total
     * @param session   the eligibility-discount selection for the current transaction; must not be
     *                  {@code null}
     * @param scheduler runs the engine call off the EDT; must not be {@code null}
     */
    public CustomerViewController(CustomerView view, CloudApiComponent cloudApi,
                                  DiscountSession session, DiscountCalcScheduler scheduler) {
        if (view == null) throw new IllegalArgumentException("view must not be null");
        if (session == null) throw new IllegalArgumentException("session must not be null");
        if (scheduler == null) throw new IllegalArgumentException("scheduler must not be null");
        this.view = view;
        this.cloudApi = cloudApi;
        this.discountSession = session;
        this.discountScheduler = scheduler;
    }

    private static DiscountCalcScheduler defaultScheduler() {
        return (work, onResult) -> {
            Thread t = new Thread(() -> {
                CloudApiComponent.CalculateResult result;
                try {
                    result = work.get();
                } catch (RuntimeException e) {
                    result = CloudApiComponent.CalculateResult.fail(
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                CloudApiComponent.CalculateResult delivered = result;
                SwingUtilities.invokeLater(() -> onResult.accept(delivered));
            }, "discount-calc");
            t.setDaemon(true);
            t.start();
        };
    }

    // ---- IController ------------------------------------------------------

    @Override
    public void onStart(PosComponent parent) {
        this.parent = parent;
        parent.register(this);
        beginNewTransaction();
        view.setVisible(true);
    }

    @Override
    public void onEnd() {
        if (parent != null) {
            parent.unregister(this);
            parent = null;
        }
        view.dispose();
    }

    // ---- IPosEventListener ------------------------------------------------

    @Override
    public Set<PosEventType> getListeningEventTypes() {
        return LISTEN_TYPES;
    }

    @Override
    public void onPosEvent(PosEvent event) {
        switch (event.getType()) {
            case QUICK_ADD_PRESSED -> handleQuickAdd(event);
            case ITEM_SCANNED -> handleScannedItem(event);
            case VOID_LINE_PRESSED -> handleVoidLine(event);
            case VOID_BASKET_CONFIRM_PRESSED -> handleVoidBasketConfirmed();
            case TOTAL_PRESSED -> handleTotal();
            case QUANTITY_CHANGED, LINE_VOIDED, ELIGIBILITY_DISCOUNT_SELECTED -> render();
            case RECEIPT_DISMISSED -> beginNewTransaction();
            default -> { /* not subscribed */ }
        }
    }

    // ---- Handlers ---------------------------------------------------------

    private void handleQuickAdd(PosEvent event) {
        addItemByUpc(event.getProperty("upc", String.class));
    }

    private void handleScannedItem(PosEvent event) {
        addItemByUpc(event.getProperty("upc", String.class));
    }

    private void addItemByUpc(String upc) {
        if (upc == null) return;
        TransactionService.AddItemOutcome outcome;
        try {
            outcome = parent.getTransactionService().addItemByUpcDetailed(upc, 1);
        } catch (RuntimeException ignored) {
            return;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("lineItem", outcome.getLineItem());
        // Attach the ladder outcome so JournalListener can record which normalisation rung the
        // scanned code resolved on. Knowing a scan only resolved on rung 3 is the difference
        // between a working integration and one quietly relying on a coincidence.
        props.put("matchedRung", outcome.getMatchedRung().name());
        props.put("matchedKey", outcome.getMatchedKey());
        props.put("scannedUpc", upc);
        parent.dispatchPosEvent(new PosEvent(PosEventType.ITEM_ADDED, props));
        // An item reached the basket — via a tapped tile or a barcode read. Either way the Quick
        // Add search keyboard, if it's up, is now stale: dismiss it without touching the search
        // text or grid filter.
        view.dismissSearchKeyboard();
        render();
    }

    private void handleVoidLine(PosEvent event) {
        LineItem selected = event.getProperty("lineItem", LineItem.class);
        if (selected == null) return;
        try {
            parent.getTransactionService().voidLine(selected);
        } catch (RuntimeException ignored) {
            return;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("lineItem", selected);
        // Re-render happens via the LINE_VOIDED subscription — no need to call render()
        // directly, and doing so would double-render (mocked view expectations would break).
        parent.dispatchPosEvent(new PosEvent(PosEventType.LINE_VOIDED, props));
    }

    private void handleVoidBasketConfirmed() {
        Transaction tx = parent.getTransactionService().getCurrentTransaction();
        if (tx == null) return;
        // Snapshot BEFORE voidBasket() — the aggregate transitions to VOIDED which zeroes
        // subtotal, and the "prior state" for journalling is only interesting because we
        // remembered it here. Voiding after Total is the more expensive path operationally.
        int itemCount = 0;
        for (LineItem li : tx.getLineItems()) {
            if (!li.isVoided()) itemCount += li.getQuantity();
        }
        BigDecimal grandTotal = tx.grandTotal();
        TransactionState priorState = tx.getState();

        try {
            parent.getTransactionService().voidBasket();
        } catch (RuntimeException ignored) {
            return;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("itemCount", itemCount);
        props.put("grandTotal", grandTotal);
        props.put("priorState", priorState.name());
        parent.dispatchPosEvent(new PosEvent(PosEventType.BASKET_VOIDED, props));
        // Reuse the same reset path a dismissed receipt takes so a voided lane lands in the same
        // usable idle state — fresh transaction, basket cleared, tender disabled, scan focus
        // restored by ScannerViewController which already listens on BASKET_VOIDED.
        beginNewTransaction();
    }

    private void handleTotal() {
        Transaction tx;
        try {
            tx = parent.getTransactionService().total();
        } catch (RuntimeException ignored) {
            return;
        }
        parent.dispatchPosEvent(new PosEvent(PosEventType.TRANSACTION_TOTALED));
        // At TOTALED the domain freezes basket mutation but still permits voiding the whole
        // transaction — a customer changing their mind at the card reader must still be able to
        // walk away. Basket input off; lifecycle input on.
        view.setBasketInputEnabled(false);
        view.setLifecycleInputEnabled(true);

        if (cloudApi == null) {
            // Phase-1 behaviour: no discount engine wired. Enable tender against the undiscounted
            // total immediately.
            view.setTenderInputEnabled(true);
            render();
            return;
        }

        // Hold tender: the cashier must not take payment against a total that's about to change.
        // Show the "Calculating Discounts" pending state so the round-trip doesn't read as a freeze,
        // then call the engine OFF the EDT — a 2s timeout inline would freeze this very repaint.
        view.setTenderInputEnabled(false);
        view.setCalculatingDiscounts(true);

        TransactionDto request = buildRequest(tx);
        Map<String, Object> reqProps = new HashMap<>();
        reqProps.put("codes", String.join(",", discountSession.getSelectedCodes()));
        reqProps.put("itemCount", nonVoidedItemCount(tx));
        parent.dispatchPosEvent(new PosEvent(PosEventType.DISCOUNT_REQUEST_SENT, reqProps));

        discountScheduler.schedule(
                () -> cloudApi.calculate(request),
                result -> applyEngineResult(tx, result));
    }

    /**
     * Handles the engine's reply on the EDT: applies each returned discount to the (still TOTALED)
     * transaction in order, or — on any failure — applies nothing and surfaces a visible error.
     * Both paths converge on tender being enabled, so a failed call can never strand the sale in
     * TOTALED with no way to pay.
     */
    private void applyEngineResult(Transaction tx, CloudApiComponent.CalculateResult result) {
        view.setCalculatingDiscounts(false);

        // If the cashier voided the basket (legal at TOTALED) while the engine was being called,
        // the transaction we captured is no longer current — the void flow already reset the view,
        // so do nothing rather than apply discounts to a dead transaction or re-enable tender.
        if (parent.getTransactionService().getCurrentTransaction() != tx
                || tx.getState() != TransactionState.TOTALED) {
            return;
        }

        if (result != null && result.ok()) {
            for (Discount d : result.discounts()) {
                // applyDiscount() requires TOTALED, which this flow already satisfies — the state
                // rule is deliberately NOT widened.
                tx.applyDiscount(d);
                Map<String, Object> props = new HashMap<>();
                props.put("discountId", d.getDiscountId());
                props.put("description", d.getDescription());
                props.put("amount", d.getAppliedAmount());
                parent.dispatchPosEvent(new PosEvent(PosEventType.DISCOUNT_APPLIED, props));
            }
        } else {
            // A sale that quietly drops a discount is worse than one that says it couldn't reach the
            // engine. Surface it; the sale still completes against the undiscounted total.
            Map<String, Object> props = new HashMap<>();
            props.put("code", "DISCOUNTS_UNAVAILABLE");
            props.put("message", "Discounts Unavailable — Continuing Without Them.");
            if (result != null && result.error() != null) props.put("detail", result.error());
            parent.dispatchPosEvent(new PosEvent(PosEventType.ERROR, props));
        }

        view.setTenderInputEnabled(true);
        render();
    }

    /** Maps the current transaction and the selected eligibility codes onto the engine's wire form. */
    private TransactionDto buildRequest(Transaction tx) {
        List<LineItemDto> lineItems = new ArrayList<>();
        for (LineItem li : tx.getLineItems()) {
            if (li.isVoided()) continue;
            lineItems.add(new LineItemDto(
                    li.getItem().getUpc(),
                    li.getItem().getDescription(),
                    li.getQuantity(),
                    li.getItem().getUnitPrice()));
        }
        return new TransactionDto(
                tx.getTransactionId(),
                tx.getCreatedAt(),
                lineItems,
                tx.subtotal(),
                new ArrayList<>(discountSession.getSelectedCodes()));
    }

    private static int nonVoidedItemCount(Transaction tx) {
        int count = 0;
        for (LineItem li : tx.getLineItems()) {
            if (!li.isVoided()) count += li.getQuantity();
        }
        return count;
    }

    // ---- State transitions ------------------------------------------------

    private void beginNewTransaction() {
        // The eligibility selection is transaction-scoped — a new sale starts with no discount.
        discountSession.clear();
        view.setCalculatingDiscounts(false);
        try {
            parent.getTransactionService().startTransaction();
        } catch (RuntimeException ignored) {
            // Service dispatched ERROR; render whatever it left behind.
        }
        // IN_PROGRESS: both basket mutation and voiding are legal. The Void basket button is
        // additionally gated on a non-empty basket by CustomerView#refreshVoidBasketButton so
        // it stays disabled until the first item is rung up.
        view.setBasketInputEnabled(true);
        view.setLifecycleInputEnabled(true);
        view.setTenderInputEnabled(false);
        render();
    }

    private void render() {
        Transaction tx = parent.getTransactionService().getCurrentTransaction();
        if (tx == null) {
            view.updateBasket(List.of(), BigDecimal.ZERO);
            view.setDiscountDescriptions(List.of());
            return;
        }
        BigDecimal subtotal = tx.subtotal();

        if (!tx.getDiscounts().isEmpty()) {
            // Post-Total: the engine's applied discounts are authoritative for the totals; the free
            // rows are driven by the engine's PROMO amounts.
            view.updateBasket(displayRowsFromEngine(tx), subtotal,
                    tx.discountTotal(), tx.taxTotal(), tx.grandTotal());
            List<String> descriptions = new ArrayList<>();
            for (Discount d : tx.getDiscounts()) {
                descriptions.add(d.getDescription() + "  -" + money(d.getAppliedAmount()));
            }
            view.setDiscountDescriptions(descriptions);
            return;
        }

        // IN_PROGRESS live preview. Two locally-computed pieces, updated on every scan/qty change so
        // the running total stays correct without a per-scan network round-trip; the engine result
        // replaces both at Total:
        //   1. buy-N-get-M promotions from the rules cached at startup — each yields an inert,
        //      indented free row and reduces the total; and
        //   2. the selected eligibility discount (see DiscountPreview).
        // Tax and grand total are composed from the combined preview the same way the aggregate
        // composes them from applied discounts (tax on subtotal − discount).
        List<PromoLine> promos = localPromoLines(tx);
        BigDecimal promoDiscount = BigDecimal.ZERO;
        for (PromoLine p : promos) {
            promoDiscount = promoDiscount.add(p.amount());
        }
        BigDecimal eligibilityDiscount =
                DiscountPreview.previewTotal(discountSession.getSelectedRules(), subtotal);
        BigDecimal previewDiscount = promoDiscount.add(eligibilityDiscount);
        if (previewDiscount.compareTo(subtotal) > 0) previewDiscount = subtotal;

        BigDecimal taxable = subtotal.subtract(previewDiscount);
        BigDecimal tax = taxable.multiply(tx.getTaxRate());
        BigDecimal total = taxable.add(tax).setScale(2, RoundingMode.HALF_UP);

        view.updateBasket(interleaveFreeRows(tx, promos), subtotal, previewDiscount, tax, total);

        List<String> descriptions = new ArrayList<>();
        for (PromoLine p : promos) {
            descriptions.add(p.rule().description() + "  -" + money(p.amount()));
        }
        for (EligibilityRule rule : discountSession.getSelectedRules()) {
            descriptions.add(rule.description() + "  -"
                    + money(DiscountPreview.previewAmount(rule, subtotal)));
        }
        view.setDiscountDescriptions(descriptions);
    }

    /** A locally-previewed promotion on one basket line: how many units are free and their value. */
    private record PromoLine(LineItem line, CloudApiComponent.PromoRule rule,
                             int freeUnits, BigDecimal amount) {
    }

    /**
     * Computes the buy-N-get-M promotions that currently apply to the basket, from the promo rules
     * cached at startup — the live equivalent of what the engine will return at Total. Empty when no
     * engine is wired or nothing qualifies.
     */
    private List<PromoLine> localPromoLines(Transaction tx) {
        if (cloudApi == null) return List.of();
        List<PromoLine> out = new ArrayList<>();
        for (LineItem li : tx.getLineItems()) {
            if (li.isVoided()) continue;
            var ruleOpt = cloudApi.promoRuleForUpc(li.getItem().getUpc());
            if (ruleOpt.isEmpty()) continue;
            CloudApiComponent.PromoRule rule = ruleOpt.get();
            int free = CloudApiComponent.freeUnitsFor(rule, li.getQuantity());
            if (free <= 0) continue;
            BigDecimal amount = li.getItem().getUnitPrice().multiply(BigDecimal.valueOf(free));
            out.add(new PromoLine(li, rule, free, amount));
        }
        return out;
    }

    /** Real lines with a {@link FreeLineItem} inserted after each line that earned a promo (preview). */
    private List<LineItem> interleaveFreeRows(Transaction tx, List<PromoLine> promos) {
        Map<LineItem, PromoLine> byLine = new HashMap<>();
        for (PromoLine p : promos) {
            byLine.put(p.line(), p);
        }
        List<LineItem> out = new ArrayList<>();
        for (LineItem li : tx.getLineItems()) {
            out.add(li);
            PromoLine p = byLine.get(li);
            if (p != null) {
                out.add(new FreeLineItem(li.getItem(), p.freeUnits(), p.amount()));
            }
        }
        return out;
    }

    /**
     * Post-Total display rows: real lines plus a {@link FreeLineItem} after each line an engine
     * PROMO discount hit, using the engine's authoritative applied amount. The target UPC and
     * free-unit count come from the promo rules cached at startup.
     */
    private List<LineItem> displayRowsFromEngine(Transaction tx) {
        List<LineItem> out = new ArrayList<>();
        for (LineItem li : tx.getLineItems()) {
            out.add(li);
            if (cloudApi == null || li.isVoided()) continue;
            for (Discount d : tx.getDiscounts()) {
                if (d.getType() != DiscountType.PROMO) continue;
                var ruleOpt = cloudApi.promoRuleByCode(d.getDiscountId());
                if (ruleOpt.isEmpty()) continue;
                CloudApiComponent.PromoRule rule = ruleOpt.get();
                if (!li.getItem().getUpc().equals(rule.targetUpc())) continue;
                int free = CloudApiComponent.freeUnitsFor(rule, li.getQuantity());
                out.add(new FreeLineItem(li.getItem(), free, d.getAppliedAmount()));
                break;
            }
        }
        return out;
    }

    private static String money(BigDecimal amount) {
        return "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

}
