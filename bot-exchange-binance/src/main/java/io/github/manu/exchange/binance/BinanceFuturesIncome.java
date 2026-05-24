package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceFuturesIncome(
        String symbol,
        String incomeType,
        BigDecimal income,
        String asset,
        String info,
        Long time,
        String transactionId,
        String tradeId
) {
}
