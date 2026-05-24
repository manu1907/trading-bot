package io.github.manu.exchange.binance;

record BinanceWebSocketApiRequest(
        String id,
        String method,
        String payload,
        String signaturePayload,
        String signature
) {
}
