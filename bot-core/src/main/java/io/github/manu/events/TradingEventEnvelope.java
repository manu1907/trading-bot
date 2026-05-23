package io.github.manu.events;

import io.github.manu.events.v1.TradingEventKey;
import org.apache.avro.specific.SpecificRecord;

import java.util.Objects;

public record TradingEventEnvelope<T extends SpecificRecord>(
        TradingEventType eventType,
        TradingEventRoute route,
        TradingEventKey key,
        T value
) {

    public TradingEventEnvelope {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (!eventType.route().equals(route)) {
            throw new IllegalArgumentException("route does not match event type: " + eventType);
        }
        if (!eventType.recordClass().isInstance(value)) {
            throw new IllegalArgumentException(
                    "value class " + value.getClass().getName() + " does not match " + eventType.recordClass().getName()
            );
        }
        if (!eventType.name().contentEquals(key.getEventType())) {
            throw new IllegalArgumentException("key event type does not match envelope event type: " + eventType);
        }
        key = copyKey(key);
    }

    @Override
    public TradingEventKey key() {
        return copyKey(key);
    }

    public static <T extends SpecificRecord> TradingEventEnvelope<T> of(
            TradingEventType eventType,
            TradingEventKey key,
            T value
    ) {
        return new TradingEventEnvelope<>(eventType, eventType.route(), key, value);
    }

    private static TradingEventKey copyKey(TradingEventKey key) {
        return TradingEventKey.newBuilder(key).build();
    }
}
