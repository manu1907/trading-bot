package io.github.manu.exchange.binance;

record BinanceFuturesForceOrderQuery(
        String symbol,
        String autoCloseType,
        Long startTime,
        Long endTime,
        Integer limit
) {
}
