package io.github.manu.exchange.binance;

record BinanceOrderHistoryQuery(
        String symbol,
        String pair,
        Long orderId,
        Long startTime,
        Long endTime,
        Integer limit,
        Boolean isolatedMargin
) {
}
