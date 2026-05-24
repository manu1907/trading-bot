package io.github.manu.exchange.binance;

record BinanceOrderListOrder(
        String symbol,
        Long orderId,
        String clientOrderId
) {
}
