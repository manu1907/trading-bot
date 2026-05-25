package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceOptionsPositionSnapshot(
        String symbol,
        String side,
        BigDecimal quantity,
        BigDecimal entryPrice,
        BigDecimal markValue,
        BigDecimal unrealizedPnl,
        BigDecimal markPrice,
        BigDecimal strikePrice,
        Long expiryDate,
        Integer priceScale,
        Integer quantityScale,
        String optionSide,
        String quoteAsset,
        Long time,
        BigDecimal bidQuantity,
        BigDecimal askQuantity
) {
}
