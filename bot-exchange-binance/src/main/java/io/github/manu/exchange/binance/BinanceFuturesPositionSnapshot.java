package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceFuturesPositionSnapshot(
        String symbol,
        String positionSide,
        BigDecimal positionAmount,
        BigDecimal entryPrice,
        BigDecimal breakEvenPrice,
        BigDecimal markPrice,
        BigDecimal unrealizedProfit,
        BigDecimal liquidationPrice,
        Integer leverage,
        BigDecimal maxQuantity,
        String marginType,
        Boolean isolated,
        Boolean autoAddMargin,
        BigDecimal isolatedMargin,
        BigDecimal isolatedWallet,
        BigDecimal notional,
        String marginAsset,
        BigDecimal initialMargin,
        BigDecimal maintMargin,
        BigDecimal positionInitialMargin,
        BigDecimal openOrderInitialMargin,
        Integer adl,
        BigDecimal bidNotional,
        BigDecimal askNotional,
        Long updateTime
) {
}
