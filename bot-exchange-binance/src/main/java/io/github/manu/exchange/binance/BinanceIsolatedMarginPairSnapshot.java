package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceIsolatedMarginPairSnapshot(
        BinanceIsolatedMarginAssetBalance baseAsset,
        BinanceIsolatedMarginAssetBalance quoteAsset,
        String symbol,
        Boolean isolatedCreated,
        Boolean enabled,
        BigDecimal marginLevel,
        String marginLevelStatus,
        BigDecimal marginRatio,
        BigDecimal indexPrice,
        BigDecimal liquidatePrice,
        BigDecimal liquidateRate,
        Boolean tradeEnabled
) {
}
