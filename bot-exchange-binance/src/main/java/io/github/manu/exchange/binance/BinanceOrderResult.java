package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceOrderResult(
        String symbol,
        Long orderId,
        String clientOrderId,
        String status,
        String side,
        String type,
        String positionSide,
        BigDecimal price,
        BigDecimal originalQuantity,
        BigDecimal executedQuantity,
        BigDecimal averagePrice,
        BigDecimal cumulativeQuote,
        Long updateTime
) {
}
