package io.github.manu.messaging;

import io.github.manu.events.TradingEventType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class TradingEventHandlerRegistry {

    private final Map<TradingEventType, List<TradingEventHandler>> handlers;

    public TradingEventHandlerRegistry(List<TradingEventHandlerRegistration> registrations) {
        Objects.requireNonNull(registrations, "registrations");
        this.handlers = registrations.stream()
                .collect(Collectors.groupingBy(TradingEventHandlerRegistration::eventType))
                .entrySet()
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .map(TradingEventHandlerRegistration::handler)
                                .toList()
                ));
    }

    public TradingEventHandler handlerFor(TradingEventType eventType) {
        Objects.requireNonNull(eventType, "eventType");
        List<TradingEventHandler> eventHandlers = handlers.get(eventType);
        if (eventHandlers != null && !eventHandlers.isEmpty()) {
            return envelope -> CompletableFuture.allOf(eventHandlers.stream()
                    .map(handler -> handler.handle(envelope))
                    .toArray(CompletableFuture[]::new));
        }
        return ignored -> CompletableFuture.failedFuture(
                new MessagingException("No handler registered for " + eventType)
        );
    }
}
