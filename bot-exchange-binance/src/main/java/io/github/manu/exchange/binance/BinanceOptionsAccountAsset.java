package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceOptionsAccountAsset(
        String asset,
        BigDecimal marginBalance,
        BigDecimal equity,
        BigDecimal available,
        BigDecimal initialMargin,
        BigDecimal maintenanceMargin,
        BigDecimal unrealizedPnl,
        BigDecimal adjustedEquity
) {
}
