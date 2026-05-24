package io.github.manu.exchange.binance;

import java.math.BigDecimal;
import java.util.List;

record BinanceIsolatedMarginAccountSnapshot(
        List<BinanceIsolatedMarginPairSnapshot> assets,
        BigDecimal totalAssetOfBtc,
        BigDecimal totalLiabilityOfBtc,
        BigDecimal totalNetAssetOfBtc
) {
    BinanceIsolatedMarginAccountSnapshot {
        assets = List.copyOf(assets);
    }
}
