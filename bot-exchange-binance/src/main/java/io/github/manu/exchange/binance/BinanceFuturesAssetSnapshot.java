package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceFuturesAssetSnapshot(
        String asset,
        BigDecimal walletBalance,
        BigDecimal unrealizedProfit,
        BigDecimal marginBalance,
        BigDecimal maintMargin,
        BigDecimal initialMargin,
        BigDecimal positionInitialMargin,
        BigDecimal openOrderInitialMargin,
        BigDecimal crossWalletBalance,
        BigDecimal crossUnrealizedPnl,
        BigDecimal availableBalance,
        BigDecimal maxWithdrawAmount,
        Long updateTime
) {
}
