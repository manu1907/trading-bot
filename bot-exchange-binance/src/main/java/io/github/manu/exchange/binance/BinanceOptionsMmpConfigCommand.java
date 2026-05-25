package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceOptionsMmpConfigCommand(
        String underlying,
        Long windowTimeInMilliseconds,
        Long frozenTimeInMilliseconds,
        BigDecimal quantityLimit,
        BigDecimal deltaLimit
) {
}
