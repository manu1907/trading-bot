package io.github.manu.exchange.binance;

import java.util.Map;

record BinanceFuturesAdlQuantile(
        String symbol,
        Map<String, Integer> quantiles
) {
    BinanceFuturesAdlQuantile {
        quantiles = Map.copyOf(quantiles);
    }
}
