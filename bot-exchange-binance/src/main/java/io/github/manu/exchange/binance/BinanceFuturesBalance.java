package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceFuturesBalance(
        String accountAlias,
        String asset,
        BigDecimal balance,
        BigDecimal crossWalletBalance,
        BigDecimal crossUnrealizedPnl,
        BigDecimal availableBalance,
        BigDecimal maxWithdrawAmount,
        BigDecimal withdrawAvailable,
        Boolean marginAvailable,
        Long updateTime
) {
}
