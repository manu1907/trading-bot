package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceFuturesForceOrder(
        Long orderId,
        String symbol,
        String pair,
        String status,
        String clientOrderId,
        BigDecimal price,
        BigDecimal averagePrice,
        BigDecimal originalQuantity,
        BigDecimal executedQuantity,
        BigDecimal cumulativeQuote,
        BigDecimal cumulativeBase,
        String timeInForce,
        String type,
        Boolean reduceOnly,
        Boolean closePosition,
        String side,
        String positionSide,
        BigDecimal stopPrice,
        String workingType,
        Boolean priceProtect,
        String originalType,
        Long time,
        Long updateTime
) {
}
