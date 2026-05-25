package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceAccountTrade(
        String symbol,
        Long id,
        Long tradeId,
        Long orderId,
        Long orderListId,
        String pair,
        String side,
        String positionSide,
        String liquidity,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal quoteQuantity,
        BigDecimal baseQuantity,
        BigDecimal realizedPnl,
        BigDecimal commission,
        String commissionAsset,
        String marginAsset,
        Integer priceScale,
        Integer quantityScale,
        String optionSide,
        String quoteAsset,
        Boolean buyer,
        Boolean maker,
        Boolean bestMatch,
        Long time
) {
}
