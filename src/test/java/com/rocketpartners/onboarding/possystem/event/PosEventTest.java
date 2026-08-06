package com.rocketpartners.onboarding.possystem.event;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PosEventTest {

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void getType_returnsConstructedType() {
        PosEvent event = new PosEvent(PosEventType.ITEM_SCANNED);
        assertThat(event.getType()).isEqualTo(PosEventType.ITEM_SCANNED);
    }

    @Test
    void constructor_acceptsNullMap() {
        PosEvent event = new PosEvent(PosEventType.ERROR, null);
        assertThat(event.getProperties()).isEmpty();
        assertThat(event.getProperty("anything", String.class)).isNull();
    }

    @Test
    void constructor_rejectsNullType() {
        assertThatThrownBy(() -> new PosEvent(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getProperty_missingKey_returnsNull() {
        PosEvent event = new PosEvent(PosEventType.ITEM_SCANNED, props("upc", "12345"));
        assertThat(event.getProperty("does-not-exist", String.class)).isNull();
    }

    @Test
    void getProperty_correctType_returnsValue() {
        PosEvent event = new PosEvent(PosEventType.ITEM_SCANNED, props("upc", "12345"));
        assertThat(event.getProperty("upc", String.class)).isEqualTo("12345");
    }

    @Test
    void getProperty_wrongType_throwsClassCastException() {
        PosEvent event = new PosEvent(PosEventType.ITEM_SCANNED, props("upc", "12345"));
        assertThatThrownBy(() -> event.getProperty("upc", Integer.class))
                .isInstanceOf(ClassCastException.class);
    }

    @Test
    void getProperty_nullValue_returnsNull() {
        PosEvent event = new PosEvent(PosEventType.ITEM_SCANNED, props("upc", null));
        assertThat(event.getProperty("upc", String.class)).isNull();
    }

    @Test
    void getPropertyWithDefault_missingKey_returnsDefault() {
        PosEvent event = new PosEvent(PosEventType.ITEM_SCANNED);
        assertThat(event.getProperty("upc", String.class, "fallback")).isEqualTo("fallback");
    }

    @Test
    void getPropertyWithDefault_wrongType_returnsDefault() {
        PosEvent event = new PosEvent(PosEventType.ITEM_SCANNED, props("upc", "12345"));
        // stored a String; ask for Integer with a default — should get the default, not throw.
        assertThat(event.getProperty("upc", Integer.class, 42)).isEqualTo(42);
    }

    @Test
    void getPropertyWithDefault_correctType_returnsStoredValue() {
        PosEvent event = new PosEvent(PosEventType.ITEM_SCANNED, props("upc", "12345"));
        assertThat(event.getProperty("upc", String.class, "fallback")).isEqualTo("12345");
    }

    @Test
    void hasProperty_reflectsPresence() {
        PosEvent event = new PosEvent(PosEventType.ITEM_SCANNED,
                props("present", "value", "presentNull", null));
        assertThat(event.hasProperty("present")).isTrue();
        assertThat(event.hasProperty("presentNull")).isTrue();
        assertThat(event.hasProperty("absent")).isFalse();
    }

    @Test
    void properties_areImmutableAfterConstruction() {
        PosEvent event = new PosEvent(PosEventType.ITEM_SCANNED, props("upc", "12345"));
        assertThatThrownBy(() -> event.getProperties().put("mutated", "nope"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void constructor_copiesProvidedMap() {
        Map<String, Object> input = new HashMap<>();
        input.put("upc", "12345");
        PosEvent event = new PosEvent(PosEventType.ITEM_SCANNED, input);
        input.put("upc", "99999");
        input.put("added-after", "sneaky");
        assertThat(event.getProperty("upc", String.class)).isEqualTo("12345");
        assertThat(event.hasProperty("added-after")).isFalse();
    }
}
