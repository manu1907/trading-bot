package io.github.manu.messaging;

import io.github.manu.events.TradingEventType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class TradingEventHandlerRegistry {

    private final Map<TradingEventType, TradingEventHandler> handlers;

    public TradingEventHandlerRegistry(List<TradingEventHandlerRegistration> registrations) {
        Objects.requireNonNull(registrations, "registrations");
        Map<TradingEventType, TradingEventHandler> collected = new EnumMap<>(TradingEventType.class);
        for (TradingEventHandlerRegistration registration : registrations) {
            TradingEventHandler existing = collected.putIfAbsent(registration.eventType(), registration.handler());
            if (existing != null) {
                throw new IllegalArgumentException("Duplicate handler for " + registration.eventType());
            }
        }
        this.handlers = Map.copyOf(collected);
    }

    public TradingEventHandler handlerFor(TradingEventType eventType) {
        Objects.requireNonNull(eventType, "eventType");
        TradingEventHandler handler = handlers.get(eventType);
        if (handler != null) {
            return handler;
        }
        return ignored -> CompletableFuture.failedFuture(
                new MessagingException("No handler registered for " + eventType)
        );
    }
}
