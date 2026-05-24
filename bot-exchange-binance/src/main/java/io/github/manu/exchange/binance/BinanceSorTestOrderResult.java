package io.github.manu.exchange.binance;

record BinanceSorTestOrderResult(
        BinanceCommissionRateSet standardCommissionForOrder,
        BinanceCommissionRateSet taxCommissionForOrder,
        BinanceCommissionDiscount discount
) {
}
