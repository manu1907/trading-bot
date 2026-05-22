package io.github.manu.exchange.binance;

import java.time.Instant;
import java.util.Map;

record BinanceRateLimitUsage(
        Instant observedAt,
        Map<String, Long> usedWeights,
        Map<String, Long> orderCounts,
        Long retryAfterSeconds
) {
    BinanceRateLimitUsage {
        usedWeights = Map.copyOf(usedWeights);
        orderCounts = Map.copyOf(orderCounts);
    }
}
