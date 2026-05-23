package io.github.manu.messaging;

import io.github.manu.events.TradingEventType;

import java.util.Objects;

public record TradingEventHandlerRegistration(
        TradingEventType eventType,
        TradingEventHandler handler
) {

    public TradingEventHandlerRegistration {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(handler, "handler");
    }
}
