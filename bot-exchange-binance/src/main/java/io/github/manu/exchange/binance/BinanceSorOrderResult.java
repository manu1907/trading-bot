package io.github.manu.exchange.binance;

import java.math.BigDecimal;
import java.util.List;

record BinanceSorOrderResult(
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
        Long workingTime,
        List<BinanceSorFill> fills,
        String workingFloor,
        String selfTradePreventionMode,
        Boolean usedSor
) {
    BinanceSorOrderResult {
        fills = List.copyOf(fills);
    }
}
