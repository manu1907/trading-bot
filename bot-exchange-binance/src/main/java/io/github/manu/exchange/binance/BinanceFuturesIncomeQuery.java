package io.github.manu.exchange.binance;

record BinanceFuturesIncomeQuery(
        String symbol,
        String incomeType,
        Long startTime,
        Long endTime,
        Integer page,
        Integer limit
) {
}
