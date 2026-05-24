package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceMarginAssetBalance(
        String asset,
        BigDecimal borrowed,
        BigDecimal free,
        BigDecimal interest,
        BigDecimal locked,
        BigDecimal netAsset
) {
}
