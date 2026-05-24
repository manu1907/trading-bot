package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceOrderAmendment(
        Long amendmentId,
        String symbol,
        String pair,
        Long orderId,
        String clientOrderId,
        Long time,
        BigDecimal priceBefore,
        BigDecimal priceAfter,
        BigDecimal originalQuantityBefore,
        BigDecimal originalQuantityAfter,
        Integer count
) {
}
