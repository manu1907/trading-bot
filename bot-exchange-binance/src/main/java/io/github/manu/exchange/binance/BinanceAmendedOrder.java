package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceAmendedOrder(
        String symbol,
        Long orderId,
        Long orderListId,
        String originalClientOrderId,
        String clientOrderId,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal executedQuantity,
        BigDecimal preventedQuantity,
        BigDecimal cumulativeQuoteQuantity,
        String status,
        String timeInForce,
        String type,
        String side,
        Long workingTime,
        String selfTradePreventionMode
) {
}
