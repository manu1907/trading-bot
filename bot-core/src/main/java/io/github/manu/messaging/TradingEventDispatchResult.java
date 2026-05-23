package io.github.manu.messaging;

import io.github.manu.events.TradingEventType;

import java.util.Objects;

public record TradingEventDispatchResult(
        TradingEventDispatchStatus status,
        TradingEventType eventType,
        String reason,
        PublishedTradingEvent publishedDeadLetter
) {

    public TradingEventDispatchResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(eventType, "eventType");
        if (status == TradingEventDispatchStatus.DEAD_LETTERED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("reason must not be blank for dead-lettered events");
        }
    }

    public static TradingEventDispatchResult handled(TradingEventType eventType) {
        return new TradingEventDispatchResult(TradingEventDispatchStatus.HANDLED, eventType, null, null);
    }

    public static TradingEventDispatchResult deadLettered(
            TradingEventType eventType,
            String reason,
            PublishedTradingEvent publishedDeadLetter
    ) {
        return new TradingEventDispatchResult(
                TradingEventDispatchStatus.DEAD_LETTERED,
                eventType,
                reason,
                Objects.requireNonNull(publishedDeadLetter, "publishedDeadLetter")
        );
    }
}
