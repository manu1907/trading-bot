package io.github.manu.messaging;

import io.github.manu.events.TradingEventType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class TradingEventHandlerRegistry {

    private final Map<TradingEventType, List<TradingEventHandler>> handlers;
    private final Map<TradingEventType, List<TradingEventHandler>> replayHandlers;

    public TradingEventHandlerRegistry(List<TradingEventHandlerRegistration> registrations) {
        Objects.requireNonNull(registrations, "registrations");
        this.handlers = handlers(registrations, TradingEventHandlerRegistration::live);
        this.replayHandlers = handlers(registrations, TradingEventHandlerRegistration::replay);
    }

    private Map<TradingEventType, List<TradingEventHandler>> handlers(
            List<TradingEventHandlerRegistration> registrations,
            Predicate<TradingEventHandlerRegistration> filter
    ) {
        return registrations.stream()
                .filter(filter)
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
        return requiredFanoutHandler(eventType, handlers.get(eventType));
    }

    public TradingEventHandler replayHandlerFor(TradingEventType eventType) {
        Objects.requireNonNull(eventType, "eventType");
        return optionalFanoutHandler(replayHandlers.get(eventType));
    }

    private TradingEventHandler requiredFanoutHandler(
            TradingEventType eventType,
            List<TradingEventHandler> eventHandlers
    ) {
        if (eventHandlers != null && !eventHandlers.isEmpty()) {
            return fanoutHandler(eventHandlers);
        }
        return ignored -> CompletableFuture.failedFuture(
                new MessagingException("No handler registered for " + eventType)
        );
    }

    private TradingEventHandler optionalFanoutHandler(List<TradingEventHandler> eventHandlers) {
        if (eventHandlers != null && !eventHandlers.isEmpty()) {
            return fanoutHandler(eventHandlers);
        }
        return ignored -> CompletableFuture.completedFuture(null);
    }

    private TradingEventHandler fanoutHandler(List<TradingEventHandler> eventHandlers) {
        return envelope -> CompletableFuture.allOf(eventHandlers.stream()
                .map(handler -> handler.handle(envelope))
                .toArray(CompletableFuture[]::new));
    }
}
