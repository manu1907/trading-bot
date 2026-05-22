package io.github.manu.exchange.binance;

import java.time.Instant;

record BinanceServerTimeSnapshot(
        Instant serverTime,
        Instant localRequestStartedAt,
        Instant localResponseReceivedAt,
        long offsetMillis,
        long roundTripMillis
) {
}
