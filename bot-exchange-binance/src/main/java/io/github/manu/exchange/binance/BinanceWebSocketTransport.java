package io.github.manu.exchange.binance;

interface BinanceWebSocketTransport {

    BinanceWebSocketConnection connect(
            BinanceWebSocketConnectionPlan plan,
            BinanceWebSocketListener listener
    );
}
