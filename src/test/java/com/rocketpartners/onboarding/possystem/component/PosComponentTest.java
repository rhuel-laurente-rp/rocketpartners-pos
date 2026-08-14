package com.rocketpartners.onboarding.possystem.component;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.repository.inmemory.InMemoryItemRepository;
import com.rocketpartners.onboarding.possystem.service.TaxService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PosComponentTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("10.00"));

    private static PosComponent component(boolean debug) {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);
        return new PosComponent(
                new InMemoryItemRepository(items),
                new TaxService(BigDecimal.ZERO),
                "Test Store",
                1,
                debug);
    }

    private static PosComponent component() {
        return component(false);
    }

    // ---- Operator session --------------------------------------------------

    @Test
    void operatorId_fromLogin_isAvailableToThePos() {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);
        PosComponent pos = new PosComponent(
                new InMemoryItemRepository(items), new TaxService(BigDecimal.ZERO),
                "Test Store", 1, false, "1234");
        assertThat(pos.getOperatorId()).isEqualTo("1234");
    }

    @Test
    void operatorId_defaultsToNull_whenConstructedWithoutLogin() {
        assertThat(component().getOperatorId()).isNull();
    }

    // ---- Listener registry -------------------------------------------------

    @Test
    void register_addsListener_andDispatchDeliversMatchingEvents() {
        PosComponent pos = component();
        RecordingListener rec = new RecordingListener(EnumSet.of(PosEventType.ITEM_ADDED));
        pos.register(rec);
        pos.dispatchPosEvent(new PosEvent(PosEventType.ITEM_ADDED));
        assertThat(rec.received).hasSize(1);
        assertThat(rec.received.get(0).getType()).isEqualTo(PosEventType.ITEM_ADDED);
    }

    @Test
    void register_isIdempotent() {
        PosComponent pos = component();
        RecordingListener rec = new RecordingListener(EnumSet.of(PosEventType.ITEM_ADDED));
        pos.register(rec);
        pos.register(rec);
        pos.dispatchPosEvent(new PosEvent(PosEventType.ITEM_ADDED));
        assertThat(rec.received).hasSize(1);
    }

    @Test
    void unregister_removesListener() {
        PosComponent pos = component();
        RecordingListener rec = new RecordingListener(EnumSet.of(PosEventType.ITEM_ADDED));
        pos.register(rec);
        pos.unregister(rec);
        pos.dispatchPosEvent(new PosEvent(PosEventType.ITEM_ADDED));
        assertThat(rec.received).isEmpty();
    }

    @Test
    void dispatch_filtersByListenerSubscription() {
        PosComponent pos = component();
        RecordingListener onlyAdded = new RecordingListener(EnumSet.of(PosEventType.ITEM_ADDED));
        pos.register(onlyAdded);
        pos.dispatchPosEvent(new PosEvent(PosEventType.ERROR));
        assertThat(onlyAdded.received).isEmpty();
    }

    @Test
    void dispatch_swallowsListenerException_andContinues() {
        PosComponent pos = component();
        IPosEventListener throwing = new IPosEventListener() {
            @Override
            public Set<PosEventType> getListeningEventTypes() {
                return EnumSet.of(PosEventType.ITEM_ADDED);
            }

            @Override
            public void onPosEvent(PosEvent event) {
                throw new RuntimeException("boom");
            }
        };
        RecordingListener afterThrower = new RecordingListener(EnumSet.of(PosEventType.ITEM_ADDED));
        pos.register(throwing);
        pos.register(afterThrower);
        pos.dispatchPosEvent(new PosEvent(PosEventType.ITEM_ADDED));
        assertThat(afterThrower.received).hasSize(1);
    }

    // ---- Controller lifecycle ---------------------------------------------

    @Test
    void addController_beforeStart_doesNotCallOnStart() {
        PosComponent pos = component();
        RecordingController c = new RecordingController();
        pos.addController(c);
        assertThat(c.startCount).isZero();
    }

    @Test
    void addController_afterStart_callsOnStartImmediately() {
        PosComponent pos = component();
        pos.start();
        RecordingController c = new RecordingController();
        pos.addController(c);
        assertThat(c.startCount).isEqualTo(1);
    }

    @Test
    void start_isIdempotent() {
        PosComponent pos = component();
        RecordingController c = new RecordingController();
        pos.addController(c);
        pos.start();
        pos.start();
        assertThat(c.startCount).isEqualTo(1);
    }

    @Test
    void shutdown_callsOnEndOnEachController() {
        PosComponent pos = component();
        RecordingController a = new RecordingController();
        RecordingController b = new RecordingController();
        pos.addController(a);
        pos.addController(b);
        pos.start();
        pos.shutdown();
        assertThat(a.endCount).isEqualTo(1);
        assertThat(b.endCount).isEqualTo(1);
    }

    @Test
    void shutdown_isIdempotent() {
        PosComponent pos = component();
        RecordingController c = new RecordingController();
        pos.addController(c);
        pos.start();
        pos.shutdown();
        pos.shutdown();
        assertThat(c.endCount).isEqualTo(1);
    }

    @Test
    void removeController_afterStart_callsOnEnd() {
        PosComponent pos = component();
        RecordingController c = new RecordingController();
        pos.addController(c);
        pos.start();
        pos.removeController(c);
        assertThat(c.endCount).isEqualTo(1);
    }

    // ---- Wiring ------------------------------------------------------------

    @Test
    void constructor_buildsTransactionServiceWithSelfAsDispatcher() {
        PosComponent pos = component();
        RecordingListener errors = new RecordingListener(EnumSet.of(PosEventType.ERROR));
        pos.register(errors);
        pos.getTransactionService().startTransaction();
        assertThatThrownBy(() -> pos.getTransactionService().addItemByUpc("nope", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(errors.received).hasSize(1);
        assertThat(errors.received.get(0).getProperty("code", String.class))
                .isEqualTo("UPC_NOT_FOUND");
    }

    @Test
    void constructor_debugFalse_addsNoInternalLogger() {
        PosComponent pos = component(false);
        assertThat(pos.getListenerCount()).isZero();
    }

    @Test
    void constructor_debugTrue_addsInternalLogger() {
        PosComponent pos = component(true);
        assertThat(pos.getListenerCount()).isEqualTo(1);
    }

    @Test
    void listeningEventTypes_includesErrorOnly() {
        assertThat(component().getListeningEventTypes())
                .containsExactly(PosEventType.ERROR);
    }

    // ---- Guards ------------------------------------------------------------

    @Test
    void constructor_rejectsNullRepository() {
        assertThatThrownBy(() -> new PosComponent(null, new TaxService(BigDecimal.ZERO), "s", 1, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNullTaxService() {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);
        assertThatThrownBy(() -> new PosComponent(new InMemoryItemRepository(items), null, "s", 1, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNullStoreName() {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);
        assertThatThrownBy(() -> new PosComponent(new InMemoryItemRepository(items),
                new TaxService(BigDecimal.ZERO), null, 1, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dispatch_rejectsNullEvent() {
        assertThatThrownBy(() -> component().dispatchPosEvent(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void register_rejectsNullListener() {
        assertThatThrownBy(() -> component().register(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Fixtures ----------------------------------------------------------

    static final class RecordingListener implements IPosEventListener {
        final Set<PosEventType> types;
        final List<PosEvent> received = new ArrayList<>();

        RecordingListener(Set<PosEventType> types) {
            this.types = types;
        }

        @Override
        public Set<PosEventType> getListeningEventTypes() {
            return types;
        }

        @Override
        public void onPosEvent(PosEvent event) {
            received.add(event);
        }
    }

    static final class RecordingController implements IController {
        int startCount;
        int endCount;

        @Override
        public void onStart(PosComponent parent) {
            startCount++;
        }

        @Override
        public void onEnd() {
            endCount++;
        }
    }
}
