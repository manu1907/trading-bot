package io.github.manu.exchange.binance;

record BinanceMarginTransferHistoryQuery(
        String asset,
        String type,
        Long startTime,
        Long endTime,
        Long current,
        Long size,
        String isolatedSymbol
) {
}
