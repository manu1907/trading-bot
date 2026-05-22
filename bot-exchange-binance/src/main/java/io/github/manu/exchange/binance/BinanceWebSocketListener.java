package io.github.manu.exchange.binance;

interface BinanceWebSocketListener {

    default void onOpen(BinanceWebSocketConnectionPlan plan) {
    }

    default void onText(String text) {
    }

    default void onError(Throwable error) {
    }

    default void onClose() {
    }
}
