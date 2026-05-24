package io.github.manu.exchange.binance;

record BinanceSpotCommissionRates(
        String symbol,
        BinanceCommissionRateSet standardCommission,
        BinanceCommissionRateSet specialCommission,
        BinanceCommissionRateSet taxCommission,
        BinanceCommissionDiscount discount
) {
}
