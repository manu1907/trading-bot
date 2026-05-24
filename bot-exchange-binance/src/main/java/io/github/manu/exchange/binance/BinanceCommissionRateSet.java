package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceCommissionRateSet(
        BigDecimal maker,
        BigDecimal taker,
        BigDecimal buyer,
        BigDecimal seller
) {
}
