package io.github.manu.messaging;

import io.github.manu.events.TradingEventEnvelope;
import org.apache.avro.specific.SpecificRecord;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class RedpandaTradingEventBus implements TradingEventBus {

    private final TradingEventPublisher publisher;
    private final DeadLetterPublisher deadLetterPublisher;

    public RedpandaTradingEventBus(
            TradingEventPublisher publisher,
            DeadLetterPublisher deadLetterPublisher
    ) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.deadLetterPublisher = Objects.requireNonNull(deadLetterPublisher, "deadLetterPublisher");
    }

    @Override
    public CompletableFuture<PublishedTradingEvent> publish(
            TradingEventEnvelope<? extends SpecificRecord> envelope
    ) {
        return publisher.publishAsync(Objects.requireNonNull(envelope, "envelope"));
    }

    @Override
    public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
        return deadLetterPublisher.publishAsync(Objects.requireNonNull(event, "event"));
    }
}
