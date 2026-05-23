package io.github.manu.messaging;

import io.github.manu.events.TradingEventEnvelope;
import org.apache.avro.specific.SpecificRecord;

import java.util.concurrent.CompletableFuture;

public interface TradingEventBus {

    CompletableFuture<PublishedTradingEvent> publish(TradingEventEnvelope<? extends SpecificRecord> envelope);

    CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event);
}
