package com.rocketpartners.onboarding.possystem.event;

import lombok.Getter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A typed event with an immutable bag of named properties.
 *
 * <p>A {@code PosEvent} carries its {@link PosEventType} plus any number of key/value properties.
 * Values are stored as {@link Object}; callers use the typed accessors below to extract them.
 * Events are immutable once constructed — the property map is defensively copied and wrapped
 * with {@link Collections#unmodifiableMap(Map)}.</p>
 *
 * <p>Property values may themselves be {@code null}. {@link #getProperty(String, Class)} returns
 * {@code null} both when the key is absent and when the stored value is {@code null};
 * {@link #hasProperty(String)} disambiguates.</p>
 */
@Getter
public class PosEvent {

    private final PosEventType type;

    private final Map<String, Object> properties;

    /**
     * Constructs an event with no properties.
     *
     * @param type the event type; must not be {@code null}
     */
    public PosEvent(PosEventType type) {
        this(type, null);
    }

    /**
     * Constructs an event with the given properties. The map is defensively copied — later
     * mutations of the caller's map do not affect this event.
     *
     * @param type       the event type; must not be {@code null}
     * @param properties initial properties; may be {@code null} or empty
     */
    public PosEvent(PosEventType type, Map<String, Object> properties) {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        this.type = type;
        Map<String, Object> copy = properties == null ? new HashMap<>() : new HashMap<>(properties);
        this.properties = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns the property at {@code key}, cast to {@code type}.
     *
     * @param <T>  requested type
     * @param key  property key
     * @param type expected class of the stored value
     * @return the value, or {@code null} if the key is absent or the stored value is {@code null}
     * @throws ClassCastException if the stored value is non-null and not assignable to {@code type}
     */
    public <T> T getProperty(String key, Class<T> type) {
        Object value = properties.get(key);
        if (value == null) return null;
        return type.cast(value);
    }

    /**
     * Returns the property at {@code key} cast to {@code type}, or {@code defaultValue} when the
     * key is absent, the stored value is {@code null}, or the stored value is not assignable to
     * {@code type}. Never throws.
     *
     * @param <T>          requested type
     * @param key          property key
     * @param type         expected class of the stored value
     * @param defaultValue value returned when the property is absent or of the wrong type
     * @return the stored value cast to {@code T}, or {@code defaultValue}
     */
    public <T> T getProperty(String key, Class<T> type, T defaultValue) {
        Object value = properties.get(key);
        if (value == null) return defaultValue;
        if (!type.isInstance(value)) return defaultValue;
        return type.cast(value);
    }

    /**
     * @param key property key
     * @return {@code true} if the event carries this key (even when the associated value is {@code null})
     */
    public boolean hasProperty(String key) {
        return properties.containsKey(key);
    }
}
