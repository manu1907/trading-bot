package io.github.manu.exchange.binance;

record BinanceCancelReplaceResult(
        int httpStatusCode,
        Integer exchangeCode,
        String exchangeMessage,
        String cancelResult,
        String newOrderResult,
        BinanceOrderResult cancelResponse,
        BinanceOrderResult newOrderResponse,
        BinanceCancelReplaceError cancelError,
        BinanceCancelReplaceError newOrderError
) {
}
