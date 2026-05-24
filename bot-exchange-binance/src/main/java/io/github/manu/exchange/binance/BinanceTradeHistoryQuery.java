package io.github.manu.exchange.binance;

record BinanceTradeHistoryQuery(
        String symbol,
        String pair,
        Long orderId,
        Long startTime,
        Long endTime,
        Long fromId,
        Integer limit,
        Boolean isolatedMargin
) {
}
