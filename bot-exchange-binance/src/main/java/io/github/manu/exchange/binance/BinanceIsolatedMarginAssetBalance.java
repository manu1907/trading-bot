package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceIsolatedMarginAssetBalance(
        String asset,
        Boolean borrowEnabled,
        BigDecimal borrowed,
        BigDecimal free,
        BigDecimal interest,
        BigDecimal locked,
        BigDecimal netAsset,
        BigDecimal netAssetOfBtc,
        Boolean repayEnabled,
        BigDecimal totalAsset
) {
}
