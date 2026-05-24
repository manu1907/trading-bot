package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceCommissionDiscount(
        boolean enabledForAccount,
        boolean enabledForSymbol,
        String discountAsset,
        BigDecimal discount
) {
}
