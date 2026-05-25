package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceOptionsMmpConfig(
        Long underlyingId,
        String underlying,
        Long windowTimeInMilliseconds,
        Long frozenTimeInMilliseconds,
        BigDecimal quantityLimit,
        BigDecimal deltaLimit,
        Long lastTriggerTime
) {
}
