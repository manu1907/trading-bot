package io.github.manu.events;

import java.util.Arrays;
import java.util.Objects;

public record SerializedTradingEvent(
        TradingEventType eventType,
        TradingEventRoute route,
        byte[] keyPayload,
        byte[] valuePayload
) {

    public SerializedTradingEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(route, "route");
        keyPayload = copy(keyPayload, "keyPayload");
        valuePayload = copy(valuePayload, "valuePayload");
    }

    @Override
    public byte[] keyPayload() {
        return Arrays.copyOf(keyPayload, keyPayload.length);
    }

    @Override
    public byte[] valuePayload() {
        return Arrays.copyOf(valuePayload, valuePayload.length);
    }

    private static byte[] copy(byte[] payload, String name) {
        Objects.requireNonNull(payload, name);
        if (payload.length == 0) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return Arrays.copyOf(payload, payload.length);
    }
}
