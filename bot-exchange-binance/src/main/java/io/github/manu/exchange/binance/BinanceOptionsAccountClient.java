package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class BinanceOptionsAccountClient {

    private final BinanceProperties binance;
    private final String apiKey;
    private final String privateCredential;
    private final BinanceOptionsAccountRequestFactory requestFactory;
    private final BinanceHttpTransport transport;
    private final ObjectMapper jsonMapper;
    private final BinanceRateLimitTracker rateLimitTracker;

    BinanceOptionsAccountClient(BinanceProperties binance,
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

    BinanceOptionsAccountClient(BinanceProperties binance,
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

    BinanceOptionsAccountClient(BinanceProperties binance,
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
        this.requestFactory = new BinanceOptionsAccountRequestFactory(binance, clock, serverTimeOffsetMillis);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.rateLimitTracker = Objects.requireNonNull(rateLimitTracker, "rateLimitTracker");
    }

    BinanceOptionsMarginAccountSnapshot marginAccount() {
        JsonNode root = readJson(send(requestFactory.marginAccount(privateCredential), "GET"));
        return new BinanceOptionsMarginAccountSnapshot(
                toAssets(root),
                toGreeks(root),
                longValue(root, "time"),
                bool(root, "canTrade").orElse(null),
                bool(root, "canDeposit").orElse(null),
                bool(root, "canWithdraw").orElse(null),
                bool(root, "reduceOnly").orElse(null),
                longValue(root, "tradeGroupId")
        );
    }

    List<BinanceOptionsPositionSnapshot> positions(String symbol) {
        JsonNode root = readJson(send(requestFactory.positions(symbol, privateCredential), "GET"));
        if (!root.isArray()) {
            throw new IllegalStateException("Expected Binance options position array response");
        }
        List<BinanceOptionsPositionSnapshot> positions = new ArrayList<>();
        for (JsonNode item : root) {
            positions.add(toPosition(item));
        }
        return List.copyOf(positions);
    }

    Optional<BinanceRateLimitUsage> currentRateLimitUsage() {
        return rateLimitTracker.current();
    }

    private BinanceHttpResponse send(BinanceSignedRequest request, String method) {
        try {
            BinanceHttpResponse response = transport.send(request, method, apiKey, binance.rest().apiKeyHeader());
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

    private List<BinanceOptionsAccountAsset> toAssets(JsonNode root) {
        JsonNode assetsNode = root.get("asset");
        if (assetsNode == null || !assetsNode.isArray()) {
            return List.of();
        }
        List<BinanceOptionsAccountAsset> assets = new ArrayList<>();
        for (JsonNode item : assetsNode) {
            assets.add(new BinanceOptionsAccountAsset(
                    text(item, "asset"),
                    decimal(item, "marginBalance"),
                    decimal(item, "equity"),
                    decimal(item, "available"),
                    decimal(item, "initialMargin"),
                    decimal(item, "maintMargin"),
                    decimal(item, "unrealizedPNL"),
                    decimal(item, "adjustedEquity")
            ));
        }
        return List.copyOf(assets);
    }

    private List<BinanceOptionsGreek> toGreeks(JsonNode root) {
        JsonNode greeksNode = root.get("greek");
        if (greeksNode == null || !greeksNode.isArray()) {
            return List.of();
        }
        List<BinanceOptionsGreek> greeks = new ArrayList<>();
        for (JsonNode item : greeksNode) {
            greeks.add(new BinanceOptionsGreek(
                    text(item, "underlying"),
                    decimal(item, "delta"),
                    decimal(item, "theta"),
                    decimal(item, "gamma"),
                    decimal(item, "vega")
            ));
        }
        return List.copyOf(greeks);
    }

    private BinanceOptionsPositionSnapshot toPosition(JsonNode node) {
        return new BinanceOptionsPositionSnapshot(
                text(node, "symbol"),
                text(node, "side"),
                decimal(node, "quantity"),
                decimal(node, "entryPrice"),
                decimal(node, "markValue"),
                decimal(node, "unrealizedPNL"),
                decimal(node, "markPrice"),
                decimal(node, "strikePrice"),
                longValue(node, "expiryDate"),
                intValue(node, "priceScale"),
                intValue(node, "quantityScale"),
                text(node, "optionSide"),
                text(node, "quoteAsset"),
                longValue(node, "time"),
                decimal(node, "bidQuantity"),
                decimal(node, "askQuantity")
        );
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

    private Integer intValue(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.required(field).asInt() : null;
    }

    private Long longValue(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.required(field).asLong() : null;
    }

    private Optional<Boolean> bool(JsonNode node, String field) {
        return node.hasNonNull(field) ? Optional.of(node.required(field).asBoolean()) : Optional.empty();
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
