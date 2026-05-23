package io.github.manu.messaging;

import io.github.manu.events.TradingEventEnvelope;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class TradingEventDispatcher {

    private final SchemaRegistryTradingEventCodec codec;
    private final DeadLetterPublisher deadLetterPublisher;
    private final Clock clock;

    public TradingEventDispatcher(
            SchemaRegistryTradingEventCodec codec,
            DeadLetterPublisher deadLetterPublisher,
            Clock clock
    ) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.deadLetterPublisher = Objects.requireNonNull(deadLetterPublisher, "deadLetterPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletableFuture<TradingEventDispatchResult> dispatch(
            ReceivedTradingEvent received,
            TradingEventHandler handler
    ) {
        Objects.requireNonNull(received, "received");
        Objects.requireNonNull(handler, "handler");

        TradingEventEnvelope<?> envelope;
        try {
            envelope = codec.deserialize(received.eventType(), received.keyPayload(), received.valuePayload());
        } catch (RuntimeException ex) {
            return publishDeadLetter(received, "decode failed: " + message(ex));
        }

        CompletableFuture<Void> handled;
        try {
            handled = handler.handle(envelope);
        } catch (RuntimeException ex) {
            return publishDeadLetter(received, "handler failed: " + message(ex));
        }

        return handled.handle((ignored, exception) -> exception)
                .thenCompose(exception -> {
                    if (exception == null) {
                        return CompletableFuture.completedFuture(
                                TradingEventDispatchResult.handled(received.eventType())
                        );
                    }
                    return publishDeadLetter(received, "handler failed: " + message(exception));
                });
    }

    private CompletableFuture<TradingEventDispatchResult> publishDeadLetter(
            ReceivedTradingEvent received,
            String reason
    ) {
        DeadLetterTradingEvent deadLetter = new DeadLetterTradingEvent(
                received.eventType(),
                received.eventType().route(),
                received.keyPayload(),
                received.valuePayload(),
                reason,
                clock.instant()
        );
        return deadLetterPublisher.publishAsync(deadLetter)
                .thenApply(published -> TradingEventDispatchResult.deadLettered(
                        received.eventType(),
                        reason,
                        published
                ));
    }

    private static String message(Throwable exception) {
        Throwable unwrapped = exception instanceof CompletionException && exception.getCause() != null
                ? exception.getCause()
                : exception;
        String message = unwrapped.getMessage();
        if (message == null || message.isBlank()) {
            return unwrapped.getClass().getSimpleName();
        }
        return message;
    }
}
