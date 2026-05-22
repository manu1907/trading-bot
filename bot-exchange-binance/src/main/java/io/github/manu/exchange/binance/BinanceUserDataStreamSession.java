package io.github.manu.exchange.binance;

import java.time.Instant;

record BinanceUserDataStreamSession(
        String mode,
        String streamId,
        Instant startedAt,
        Instant expiresAt,
        Instant renewAfter
) {
}
