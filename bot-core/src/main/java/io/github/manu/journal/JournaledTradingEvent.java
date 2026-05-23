package io.github.manu.journal;

import io.github.manu.events.SerializedTradingEvent;

import java.util.Objects;

public record JournaledTradingEvent(long index, SerializedTradingEvent event) {

    public JournaledTradingEvent {
        Objects.requireNonNull(event, "event");
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
    }
}
