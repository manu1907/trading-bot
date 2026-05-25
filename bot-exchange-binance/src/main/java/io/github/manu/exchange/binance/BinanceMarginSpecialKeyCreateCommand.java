package io.github.manu.exchange.binance;

record BinanceMarginSpecialKeyCreateCommand(
        String apiName,
        String symbol,
        String ip,
        String publicKey,
        String permissionMode
) {
}
