package io.github.manu.exchange.binance;

import java.math.BigDecimal;
import java.util.List;

record BinanceFuturesAccountSnapshot(
        BigDecimal totalInitialMargin,
        BigDecimal totalMaintMargin,
        BigDecimal totalWalletBalance,
        BigDecimal totalUnrealizedProfit,
        BigDecimal totalMarginBalance,
        BigDecimal totalPositionInitialMargin,
        BigDecimal totalOpenOrderInitialMargin,
        BigDecimal totalCrossWalletBalance,
        BigDecimal totalCrossUnrealizedPnl,
        BigDecimal availableBalance,
        BigDecimal maxWithdrawAmount,
        Boolean canDeposit,
        Boolean canTrade,
        Boolean canWithdraw,
        Integer feeTier,
        Long updateTime,
        List<BinanceFuturesAssetSnapshot> assets,
        List<BinanceFuturesPositionSnapshot> positions
) {
    BinanceFuturesAccountSnapshot {
        assets = List.copyOf(assets);
        positions = List.copyOf(positions);
    }
}
