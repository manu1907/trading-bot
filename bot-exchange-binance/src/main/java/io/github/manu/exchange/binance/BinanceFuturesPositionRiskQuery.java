package io.github.manu.exchange.binance;

record BinanceFuturesPositionRiskQuery(
        String symbol,
        String pair,
        String marginAsset
) {
}
