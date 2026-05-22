package io.github.manu.exchange.binance;

import java.net.URI;

record BinanceSignedRequest(
        URI uri,
        String payload,
        String signature
) {
}
