package io.github.manu.messaging;

import io.github.manu.events.TradingEventRoute;
import io.github.manu.events.TradingEventType;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public record DeadLetterTradingEvent(
        TradingEventType eventType,
        TradingEventRoute route,
        byte[] keyPayload,
        byte[] valuePayload,
        String reason,
        Instant failedAt
) {

    public DeadLetterTradingEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(route, "route");
        keyPayload = copy(keyPayload, "keyPayload");
        valuePayload = copy(valuePayload, "valuePayload");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        Objects.requireNonNull(failedAt, "failedAt");
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
