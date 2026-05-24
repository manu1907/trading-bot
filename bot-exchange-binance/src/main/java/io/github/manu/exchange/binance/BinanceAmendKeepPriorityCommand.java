package io.github.manu.exchange.binance;

import java.math.BigDecimal;

record BinanceAmendKeepPriorityCommand(
        String symbol,
        Long orderId,
        String originalClientOrderId,
        String newClientOrderId,
        BigDecimal newQuantity
) {
}
