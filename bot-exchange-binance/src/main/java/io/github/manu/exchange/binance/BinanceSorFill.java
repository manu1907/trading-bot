package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceSorFill(
        String matchType,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal commission,
        String commissionAsset,
        Long tradeId,
        Long allocationId
) {
}
