package io.github.manu.messaging;

import io.github.manu.events.TradingEventEnvelope;
import org.apache.avro.specific.SpecificRecord;

import java.util.concurrent.CompletableFuture;

public interface TradingEventPublisher {

    CompletableFuture<PublishedTradingEvent> publishAsync(TradingEventEnvelope<? extends SpecificRecord> envelope);
}
