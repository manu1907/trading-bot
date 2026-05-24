package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceMarginTransferRecord(
        BigDecimal amount,
        String asset,
        String status,
        Long timestamp,
        Long transactionId,
        String type,
        String transFrom,
        String transTo,
        String fromSymbol,
        String toSymbol
) {
}
