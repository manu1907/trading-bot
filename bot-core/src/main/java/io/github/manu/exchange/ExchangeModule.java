package io.github.manu.exchange;

import java.util.concurrent.CompletableFuture;

public interface ExchangeModule {
    String provider();

    void configure(ResolvedExchangeConfig config);

    default CompletableFuture<Void> applyMutableConfig(ResolvedExchangeConfig config) {
        configure(config);
        return CompletableFuture.completedFuture(null);
    }

    CompletableFuture<Void> connect();

    CompletableFuture<Void> disconnect();

    default CompletableFuture<Void> enterReduceOnlyMode() {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> ensureProtectiveOrders() {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> cancelOpenOrders() {
        return CompletableFuture.completedFuture(null);
    }
}
