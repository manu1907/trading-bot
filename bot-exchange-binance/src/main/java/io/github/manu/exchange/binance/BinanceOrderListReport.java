package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceOrderListReport(
        String symbol,
        Long orderId,
        Long orderListId,
        String clientOrderId,
        Long transactTime,
        BigDecimal price,
        BigDecimal originalQuantity,
        BigDecimal executedQuantity,
        BigDecimal originalQuoteOrderQuantity,
        BigDecimal cumulativeQuoteQuantity,
        String status,
        String timeInForce,
        String type,
        String side,
        BigDecimal stopPrice,
        BigDecimal icebergQuantity,
        Long workingTime,
        String selfTradePreventionMode
) {
}
