package io.github.manu.exchange.binance;

import java.util.List;

record BinanceCancelMultipleOrdersQuery(
        String symbol,
        List<Long> orderIds,
        List<String> originalClientOrderIds
) {
    BinanceCancelMultipleOrdersQuery {
        orderIds = orderIds == null ? List.of() : List.copyOf(orderIds);
        originalClientOrderIds = originalClientOrderIds == null ? List.of() : List.copyOf(originalClientOrderIds);
    }
}
