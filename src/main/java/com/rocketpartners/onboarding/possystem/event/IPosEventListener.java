package com.rocketpartners.onboarding.possystem.event;

import java.util.Set;

/**
 * Something that consumes {@link PosEvent}s.
 *
 * <p>Listeners are explicit about what they subscribe to via {@link #getListeningEventTypes()}.
 * A dispatcher should filter by that set before invoking {@link #onPosEvent(PosEvent)}; the
 * listener itself may still cross-check the event's type inside its handler, but is not required
 * to.</p>
 *
 * <p>Typical implementations return an {@code EnumSet.of(...)} of the types they care about.
 * An empty set means the listener will never be invoked — useful for temporarily muting a
 * listener without unregistering.</p>
 */
public interface IPosEventListener {

    /**
     * @return the event types this listener wants; must not be {@code null}
     */
    Set<PosEventType> getListeningEventTypes();

    /**
     * Invoked by a dispatcher when an event whose type matches
     * {@link #getListeningEventTypes()} is dispatched.
     *
     * @param event the event; never {@code null}
     */
    void onPosEvent(PosEvent event);
}
