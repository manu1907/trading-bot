package io.github.manu.exchange.binance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

final class BinanceJdkHttpTransport implements BinanceHttpTransport {

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    BinanceJdkHttpTransport(Duration connectTimeout, Duration requestTimeout) {
        this(HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .build(), requestTimeout);
    }

    BinanceJdkHttpTransport(HttpClient httpClient, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    @Override
    public BinanceHttpResponse sendPublic(URI uri, String method) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        return new BinanceHttpResponse(response.statusCode(), response.body(), response.headers().map());
    }

    @Override
    public BinanceHttpResponse send(BinanceSignedRequest request,
                                    String method,
                                    String apiKey,
                                    String apiKeyHeader) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(request.uri())
                .timeout(requestTimeout)
                .header(apiKeyHeader, apiKey)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        return new BinanceHttpResponse(response.statusCode(), response.body(), response.headers().map());
    }
}
