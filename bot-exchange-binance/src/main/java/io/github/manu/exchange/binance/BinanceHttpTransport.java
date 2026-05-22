package io.github.manu.exchange.binance;

import java.io.IOException;
import java.net.URI;

interface BinanceHttpTransport {

    BinanceHttpResponse sendPublic(URI uri, String method) throws IOException, InterruptedException;

    BinanceHttpResponse send(BinanceSignedRequest request,
                             String method,
                             String apiKey,
                             String apiKeyHeader) throws IOException, InterruptedException;
}
