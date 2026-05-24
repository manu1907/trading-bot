package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceAccountTrade(
        String symbol,
        Long id,
        Long orderId,
        Long orderListId,
        String pair,
        String side,
        String positionSide,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal quoteQuantity,
        BigDecimal baseQuantity,
        BigDecimal realizedPnl,
        BigDecimal commission,
        String commissionAsset,
        String marginAsset,
        Boolean buyer,
        Boolean maker,
        Boolean bestMatch,
        Long time
) {
}
