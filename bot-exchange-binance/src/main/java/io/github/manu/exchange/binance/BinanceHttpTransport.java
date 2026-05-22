package io.github.manu.exchange.binance;

import java.io.IOException;

interface BinanceHttpTransport {

    BinanceHttpResponse send(BinanceSignedRequest request,
                             String method,
                             String apiKey,
                             String apiKeyHeader) throws IOException, InterruptedException;
}
