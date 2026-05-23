package io.github.manu.messaging;

import io.github.manu.events.TradingEventType;

import java.util.Objects;

public record PublishedTradingEvent(
        TradingEventType eventType,
        String topic,
        int partition,
        long offset
) {

    public PublishedTradingEvent {
        Objects.requireNonNull(eventType, "eventType");
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (partition < 0) {
            throw new IllegalArgumentException("partition must not be negative");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
    }
}
