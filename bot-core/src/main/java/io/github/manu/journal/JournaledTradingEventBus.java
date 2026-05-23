package io.github.manu.journal;

import io.github.manu.events.SerializedTradingEvent;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventMessageCodec;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import org.apache.avro.specific.SpecificRecord;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class JournaledTradingEventBus implements TradingEventBus {

    private final TradingEventBus delegate;
    private final TradingEventJournal journal;
    private final TradingEventMessageCodec codec;

    public JournaledTradingEventBus(TradingEventBus delegate, TradingEventJournal journal) {
        this(delegate, journal, new TradingEventMessageCodec());
    }

    JournaledTradingEventBus(
            TradingEventBus delegate,
            TradingEventJournal journal,
            TradingEventMessageCodec codec
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public CompletableFuture<PublishedTradingEvent> publish(
            TradingEventEnvelope<? extends SpecificRecord> envelope
    ) {
        Objects.requireNonNull(envelope, "envelope");
        try {
            SerializedTradingEvent serialized = codec.serialize(envelope);
            journal.append(serialized);
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return delegate.publish(envelope);
    }

    @Override
    public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
        return delegate.publishDeadLetter(Objects.requireNonNull(event, "event"));
    }
}
