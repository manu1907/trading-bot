package io.github.manu.exchange.binance;

record BinanceMarginSpecialKeyDeleteCommand(
        String apiKey,
        String apiName,
        String symbol
) {
}
