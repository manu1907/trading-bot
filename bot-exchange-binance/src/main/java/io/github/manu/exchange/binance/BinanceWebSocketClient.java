package io.github.manu.exchange.binance;

import java.util.Objects;

final class BinanceWebSocketClient {

    private final BinanceWebSocketTransport transport;

    BinanceWebSocketClient(BinanceWebSocketTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport is required");
    }

    BinanceWebSocketConnection connect(
            BinanceWebSocketConnectionPlan plan,
            BinanceWebSocketListener listener
    ) {
        Objects.requireNonNull(plan, "plan is required");
        Objects.requireNonNull(listener, "listener is required");

        return Objects.requireNonNull(
                transport.connect(plan, listener),
                "transport returned no connection"
        );
    }
}
