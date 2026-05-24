package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceFuturesFundingRate(
        String symbol,
        BigDecimal fundingRate,
        Long fundingTime,
        BigDecimal markPrice
) {
}
