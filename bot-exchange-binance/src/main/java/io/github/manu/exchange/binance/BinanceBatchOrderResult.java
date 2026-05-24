package io.github.manu.exchange.binance;

record BinanceBatchOrderResult(
        BinanceOrderResult order,
        Integer code,
        String message
) {
}
