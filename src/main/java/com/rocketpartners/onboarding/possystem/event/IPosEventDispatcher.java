package com.rocketpartners.onboarding.possystem.event;

/**
 * Something that emits {@link PosEvent}s.
 *
 * <p>How a dispatcher tracks its listeners is implementation-defined. A class that both emits
 * and holds listeners will typically also implement {@link IPosEventManager} and reuse its
 * registry; a class that only pushes into a shared manager will implement just this interface
 * and hold a reference to the manager.</p>
 *
 * <p>A single class may implement any combination of {@code IPosEventDispatcher},
 * {@link IPosEventListener}, and {@link IPosEventManager} — {@code PosComponent} is expected
 * to implement all three.</p>
 */
public interface IPosEventDispatcher {

    /**
     * Sends the event to interested listeners.
     *
     * @param event the event to dispatch; must not be {@code null}
     */
    void dispatchPosEvent(PosEvent event);
}
