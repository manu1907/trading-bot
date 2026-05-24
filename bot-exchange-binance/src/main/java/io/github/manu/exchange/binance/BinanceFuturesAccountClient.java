package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

final class BinanceFuturesAccountClient {

    private final BinanceProperties binance;
    private final String apiKey;
    private final String privateCredential;
    private final BinanceFuturesAccountRequestFactory requestFactory;
    private final BinanceHttpTransport transport;
    private final ObjectMapper jsonMapper;
    private final BinanceRateLimitTracker rateLimitTracker;

    BinanceFuturesAccountClient(BinanceProperties binance,
                                String apiKey,
                                String privateCredential,
                                Clock clock,
                                long serverTimeOffsetMillis) {
        this(
                binance,
                apiKey,
                privateCredential,
                clock,
                serverTimeOffsetMillis,
                new BinanceJdkHttpTransport(
                        Duration.ofMillis(binance.rest().connectTimeoutMillis()),
                        Duration.ofMillis(binance.rest().responseTimeoutMillis())
                ),
                JsonMapperFactory.create(),
                new BinanceRateLimitTracker(clock)
        );
    }

    BinanceFuturesAccountClient(BinanceProperties binance,
                                String apiKey,
                                String privateCredential,
                                Clock clock,
                                long serverTimeOffsetMillis,
                                BinanceHttpTransport transport,
                                ObjectMapper jsonMapper) {
        this(
                binance,
                apiKey,
                privateCredential,
                clock,
                serverTimeOffsetMillis,
                transport,
                jsonMapper,
                new BinanceRateLimitTracker(clock)
        );
    }

    BinanceFuturesAccountClient(BinanceProperties binance,
                                String apiKey,
                                String privateCredential,
                                Clock clock,
                                long serverTimeOffsetMillis,
                                BinanceHttpTransport transport,
                                ObjectMapper jsonMapper,
                                BinanceRateLimitTracker rateLimitTracker) {
        this.binance = Objects.requireNonNull(binance, "binance");
        this.apiKey = requireText(apiKey, "apiKey");
        this.privateCredential = requireText(privateCredential, "privateCredential");
        this.requestFactory = new BinanceFuturesAccountRequestFactory(binance, clock, serverTimeOffsetMillis);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.rateLimitTracker = Objects.requireNonNull(rateLimitTracker, "rateLimitTracker");
    }

    BinanceFuturesAccountAck changePositionMode(String positionMode) {
        return parseAck(send(requestFactory.changePositionMode(positionMode, privateCredential)));
    }

    BinanceFuturesAccountAck changeMarginType(String symbol, String marginType) {
        return parseAck(send(requestFactory.changeMarginType(symbol, marginType, privateCredential)));
    }

    BinanceFuturesLeverageResult changeInitialLeverage(String symbol, int leverage) {
        JsonNode root = readJson(send(requestFactory.changeInitialLeverage(symbol, leverage, privateCredential)));
        return new BinanceFuturesLeverageResult(
                text(root, "symbol"),
                root.required("leverage").asInt(),
                decimal(root, "maxNotionalValue"),
                decimal(root, "maxQty")
        );
    }

    BinanceFuturesAccountAck changeMultiAssetsMode(boolean multiAssetsMode) {
        return parseAck(send(requestFactory.changeMultiAssetsMode(multiAssetsMode, privateCredential)));
    }

    Optional<BinanceRateLimitUsage> currentRateLimitUsage() {
        return rateLimitTracker.current();
    }

    private BinanceHttpResponse send(BinanceSignedRequest request) {
        try {
            BinanceHttpResponse response = transport.send(request, "POST", apiKey, binance.rest().apiKeyHeader());
            rateLimitTracker.observe(binance.rest(), response);
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw toApiException(response);
            }
            return response;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to communicate with Binance API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while communicating with Binance API", e);
        }
    }

    private BinanceApiException toApiException(BinanceHttpResponse response) {
        JsonNode root = readJson(response);
        Integer exchangeCode = root.hasNonNull("code") ? root.required("code").asInt() : null;
        String message = root.hasNonNull("msg") ? root.required("msg").asString() : "HTTP " + response.statusCode();
        return new BinanceApiException(response.statusCode(), exchangeCode, message);
    }

    private BinanceFuturesAccountAck parseAck(BinanceHttpResponse response) {
        JsonNode root = readJson(response);
        return new BinanceFuturesAccountAck(root.required("code").asInt(), text(root, "msg"));
    }

    private JsonNode readJson(BinanceHttpResponse response) {
        return jsonMapper.readTree(response.body());
    }

    private String text(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        String value = node.required(field).asString();
        return value.isBlank() ? null : value;
    }

    private BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : new BigDecimal(value);
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
