package com.rocketpartners.onboarding.possystem.component;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.IPosEventManager;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.repository.ItemRepository;
import com.rocketpartners.onboarding.possystem.service.TaxService;
import com.rocketpartners.onboarding.possystem.service.TransactionService;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The main driver: holds transaction state (via {@link TransactionService}), routes
 * {@link PosEvent}s between listeners, and owns any number of child {@link IController}s.
 *
 * <p>Implements all three event interfaces:</p>
 * <ul>
 *   <li>{@link IPosEventManager} — {@link #register}/{@link #unregister} listeners.</li>
 *   <li>{@link IPosEventDispatcher} — {@link #dispatchPosEvent} broadcasts to matching listeners.</li>
 *   <li>{@link IPosEventListener} — reacts to {@link PosEventType#ERROR} today; more types later.</li>
 * </ul>
 *
 * <p>Per {@code docs/Phase 1/event-flow.md}, {@code PosComponent} is the only class allowed to
 * mutate transaction state, and every cross-component interaction is a {@link PosEvent}, never a
 * direct method reference. This class deliberately does not import Swing or {@code display}
 * types — it talks to the display layer via events.</p>
 */
public class PosComponent implements IPosEventDispatcher, IPosEventListener, IPosEventManager {

    private static final Set<PosEventType> LISTEN_TYPES =
            Collections.unmodifiableSet(EnumSet.of(PosEventType.ERROR));

    @Getter
    private final TransactionService transactionService;

    @Getter
    private final String storeName;

    @Getter
    private final int laneNumber;

    private final boolean debug;

    private final LinkedHashSet<IPosEventListener> listeners = new LinkedHashSet<>();
    private final LinkedHashSet<IController> controllers = new LinkedHashSet<>();

    private boolean started;
    private boolean shutdown;

    /**
     * @param itemRepository pricebook lookup; must not be {@code null}
     * @param taxService     supplies the flat tax rate; must not be {@code null}
     * @param storeName      store label for receipts/journal; must not be {@code null}
     * @param laneNumber     terminal id; any int
     * @param debug          when {@code true}, registers an internal listener that traces every
     *                       dispatched event to {@code System.err}
     */
    public PosComponent(ItemRepository itemRepository, TaxService taxService,
                        String storeName, int laneNumber, boolean debug) {
        if (itemRepository == null) throw new IllegalArgumentException("itemRepository must not be null");
        if (taxService == null) throw new IllegalArgumentException("taxService must not be null");
        if (storeName == null) throw new IllegalArgumentException("storeName must not be null");
        this.storeName = storeName;
        this.laneNumber = laneNumber;
        this.debug = debug;
        this.transactionService = new TransactionService(itemRepository, taxService, this);
        if (debug) {
            register(new DebugLogger());
        }
    }

    // ---- IPosEventManager ---------------------------------------------------

    @Override
    public void register(IPosEventListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        listeners.add(listener);
    }

    @Override
    public void unregister(IPosEventListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        listeners.remove(listener);
    }

    // ---- IPosEventDispatcher ------------------------------------------------

    @Override
    public void dispatchPosEvent(PosEvent event) {
        if (event == null) throw new IllegalArgumentException("event must not be null");
        // Snapshot so listeners can (un)register without ConcurrentModificationException.
        List<IPosEventListener> snapshot = new ArrayList<>(listeners);
        for (IPosEventListener listener : snapshot) {
            Set<PosEventType> types = listener.getListeningEventTypes();
            if (types == null || !types.contains(event.getType())) continue;
            try {
                listener.onPosEvent(event);
            } catch (RuntimeException e) {
                System.err.println("PosComponent: listener " + listener + " threw on "
                        + event.getType() + ": " + e);
            }
        }
    }

    // ---- IPosEventListener --------------------------------------------------

    @Override
    public Set<PosEventType> getListeningEventTypes() {
        return LISTEN_TYPES;
    }

    @Override
    public void onPosEvent(PosEvent event) {
        if (event.getType() == PosEventType.ERROR && debug) {
            System.err.println("PosComponent: ERROR " + event.getProperties());
        }
    }

    // ---- Controller lifecycle ----------------------------------------------

    /**
     * Adds a child controller. If this component has already been {@link #start()}ed, the
     * controller's {@link IController#onStart(PosComponent)} is invoked immediately.
     *
     * @param controller the controller to add; must not be {@code null}
     */
    public void addController(IController controller) {
        if (controller == null) throw new IllegalArgumentException("controller must not be null");
        if (controllers.add(controller) && started) {
            safeOnStart(controller);
        }
    }

    /**
     * Removes a child controller, invoking {@link IController#onEnd()} on it if this component
     * has been started.
     *
     * @param controller the controller to remove
     */
    public void removeController(IController controller) {
        if (controller == null) throw new IllegalArgumentException("controller must not be null");
        if (controllers.remove(controller) && started) {
            safeOnEnd(controller);
        }
    }

    /**
     * Starts all registered controllers. Idempotent — a second call is a no-op.
     */
    public void start() {
        if (started) return;
        started = true;
        for (IController c : controllers) {
            safeOnStart(c);
        }
    }

    /**
     * Ends all registered controllers in reverse order and clears the registry. Idempotent.
     */
    public void shutdown() {
        if (shutdown) return;
        shutdown = true;
        List<IController> reversed = new ArrayList<>(controllers);
        Collections.reverse(reversed);
        for (IController c : reversed) {
            safeOnEnd(c);
        }
        controllers.clear();
        listeners.clear();
    }

    // Package-private test helpers.

    int getListenerCount() {
        return listeners.size();
    }

    int getControllerCount() {
        return controllers.size();
    }

    boolean isStarted() {
        return started;
    }

    boolean isShutdown() {
        return shutdown;
    }

    // ---- helpers -----------------------------------------------------------

    private void safeOnStart(IController c) {
        try {
            c.onStart(this);
        } catch (RuntimeException e) {
            System.err.println("PosComponent: controller " + c + " threw on onStart: " + e);
        }
    }

    private void safeOnEnd(IController c) {
        try {
            c.onEnd();
        } catch (RuntimeException e) {
            System.err.println("PosComponent: controller " + c + " threw on onEnd: " + e);
        }
    }

    private final class DebugLogger implements IPosEventListener {
        private final Set<PosEventType> all =
                Collections.unmodifiableSet(EnumSet.allOf(PosEventType.class));

        @Override
        public Set<PosEventType> getListeningEventTypes() {
            return all;
        }

        @Override
        public void onPosEvent(PosEvent event) {
            System.err.println("[POS] " + event.getType() + " " + event.getProperties());
        }
    }
}
