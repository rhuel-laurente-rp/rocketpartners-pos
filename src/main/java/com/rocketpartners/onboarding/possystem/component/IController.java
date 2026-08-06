package com.rocketpartners.onboarding.possystem.component;

/**
 * A child of {@link PosComponent} with a simple lifecycle.
 *
 * <p>The POS is event-driven, not tick-driven — there is no per-frame {@code update()} in this
 * lifecycle. Controllers do their work in response to {@code PosEvent}s. Typical usage:</p>
 * <ul>
 *   <li>{@link #onStart(PosComponent)} — obtain the parent's {@code IPosEventManager} and register
 *       the controller (or a delegate listener) for the event types it cares about.</li>
 *   <li>{@link #onEnd()} — unregister listeners, release any resources.</li>
 * </ul>
 */
public interface IController {

    /**
     * Called once when the controller becomes active — either at {@link PosComponent#start()}
     * time, or immediately if it is registered on an already-started {@code PosComponent}.
     *
     * @param parent the owning driver; never {@code null}
     */
    void onStart(PosComponent parent);

    /**
     * Called once when the controller is being retired — at {@link PosComponent#shutdown()} time
     * or when it is explicitly removed from the parent.
     */
    void onEnd();
}
