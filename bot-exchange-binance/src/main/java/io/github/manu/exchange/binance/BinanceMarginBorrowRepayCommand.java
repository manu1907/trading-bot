package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceMarginBorrowRepayCommand(
        String asset,
        BigDecimal amount,
        String type,
        boolean isolated,
        String symbol
) {
}
