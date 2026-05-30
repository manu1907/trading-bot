package io.github.manu.messaging;

import io.github.manu.events.TradingEventType;

import java.util.Objects;

public record TradingEventHandlerRegistration(
        TradingEventType eventType,
        TradingEventHandler handler,
        boolean live,
        boolean replay
) {

    public TradingEventHandlerRegistration {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(handler, "handler");
    }

    public TradingEventHandlerRegistration(TradingEventType eventType, TradingEventHandler handler) {
        this(eventType, handler, true, true);
    }

    public static TradingEventHandlerRegistration liveOnly(
            TradingEventType eventType,
            TradingEventHandler handler
    ) {
        return new TradingEventHandlerRegistration(eventType, handler, true, false);
    }

    public static TradingEventHandlerRegistration replayOnly(
            TradingEventType eventType,
            TradingEventHandler handler
    ) {
        return new TradingEventHandlerRegistration(eventType, handler, false, true);
    }
}
