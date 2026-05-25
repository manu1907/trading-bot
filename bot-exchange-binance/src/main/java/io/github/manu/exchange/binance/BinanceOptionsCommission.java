package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceOptionsCommission(
        String underlying,
        BigDecimal makerFee,
        BigDecimal takerFee
) {
}
