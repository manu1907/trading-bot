package io.github.manu.exchange.binance;

record BinanceFuturesFundingRateQuery(
        String symbol,
        Long startTime,
        Long endTime,
        Integer limit
) {
}
