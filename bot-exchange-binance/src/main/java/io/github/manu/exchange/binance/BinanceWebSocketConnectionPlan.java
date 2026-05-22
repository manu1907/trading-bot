package io.github.manu.exchange.binance;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

record BinanceWebSocketConnectionPlan(
        URI uri,
        BinanceWebSocketMode mode,
        BinanceWebSocketRoute route,
        List<String> streams,
        Instant createdAt,
        Instant expiresAt,
        Instant reconnectAt,
        Duration serverPingInterval,
        Duration pongTimeout,
        int maxIncomingMessagesPerSecond
) {
    BinanceWebSocketConnectionPlan {
        streams = List.copyOf(streams);
    }
}
