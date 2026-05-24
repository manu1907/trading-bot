package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceModifyOrderCommand(
        String symbol,
        Long orderId,
        String originalClientOrderId,
        String side,
        BigDecimal quantity,
        BigDecimal price,
        String priceMatch
) {
}
