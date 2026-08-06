package com.rocketpartners.onboarding.possystem.event;

/**
 * Listener registry for {@link PosEvent}s.
 *
 * <p>Separates the registry role from {@link IPosEventDispatcher} (emitting events) and
 * {@link IPosEventListener} (consuming them). A class may implement any combination of the
 * three — typically {@code PosComponent} implements all three: it registers listeners, receives
 * input events, and dispatches lifecycle notifications.</p>
 *
 * <p>Registration is idempotent: calling {@link #register(IPosEventListener)} with a listener
 * that is already registered leaves the registry unchanged. Unregistering a listener that
 * isn't present is a no-op.</p>
 */
public interface IPosEventManager {

    /**
     * Adds {@code listener} to the registry. No-op if the listener is already registered.
     *
     * @param listener the listener to add; must not be {@code null}
     */
    void register(IPosEventListener listener);

    /**
     * Removes {@code listener} from the registry. No-op if the listener isn't registered.
     *
     * @param listener the listener to remove; must not be {@code null}
     */
    void unregister(IPosEventListener listener);
}
