package io.github.manu.exchange.binance;

record BinanceModifyOrderHistoryQuery(
        String symbol,
        Long orderId,
        String originalClientOrderId,
        Long startTime,
        Long endTime,
        Integer limit
) {
}
