package io.github.manu.exchange.binance;

import java.util.List;

record BinanceOrderListResult(
        Long orderListId,
        String contingencyType,
        String listStatusType,
        String listOrderStatus,
        String listClientOrderId,
        Long transactionTime,
        String symbol,
        List<BinanceOrderListOrder> orders,
        List<BinanceOrderListReport> orderReports
) {
    BinanceOrderListResult {
        orders = List.copyOf(orders);
        orderReports = List.copyOf(orderReports);
    }
}
