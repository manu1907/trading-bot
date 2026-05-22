package io.github.manu.exchange.binance;

import java.util.List;
import java.util.Map;

record BinanceHttpResponse(
        int statusCode,
        String body,
        Map<String, List<String>> headers
) {
    BinanceHttpResponse(int statusCode, String body) {
        this(statusCode, body, Map.of());
    }

    BinanceHttpResponse {
        headers = Map.copyOf(headers);
    }
}
