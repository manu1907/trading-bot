package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceOptionsGreek(
        String underlying,
        BigDecimal delta,
        BigDecimal theta,
        BigDecimal gamma,
        BigDecimal vega
) {
}
