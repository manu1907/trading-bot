package io.github.manu.exchange.binance;

record BinanceOptionsAutoCancelCommand(
        String underlying,
        Long countdownTime
) {
}
