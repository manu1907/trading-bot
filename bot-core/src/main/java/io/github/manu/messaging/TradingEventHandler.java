package io.github.manu.messaging;

import io.github.manu.events.TradingEventEnvelope;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface TradingEventHandler {

    CompletableFuture<Void> handle(TradingEventEnvelope<?> envelope);
}
