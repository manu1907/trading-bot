package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceFuturesLeverageResult(
        String symbol,
        int leverage,
        BigDecimal maxNotionalValue,
        BigDecimal maxQty
) {
}
