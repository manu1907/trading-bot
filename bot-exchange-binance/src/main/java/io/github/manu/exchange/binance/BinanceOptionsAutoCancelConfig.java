package io.github.manu.exchange.binance;

record BinanceOptionsAutoCancelConfig(
        String underlying,
        Long countdownTime
) {
}
