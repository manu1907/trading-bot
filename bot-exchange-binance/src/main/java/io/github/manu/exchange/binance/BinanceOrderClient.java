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

final class BinanceOrderClient {

    private final BinanceProperties binance;
    private final String apiKey;
    private final String privateCredential;
    private final BinanceOrderRequestFactory requestFactory;
    private final BinanceHttpTransport transport;
    private final ObjectMapper jsonMapper;
    private final BinanceRateLimitTracker rateLimitTracker;

    BinanceOrderClient(BinanceProperties binance,
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

    BinanceOrderClient(BinanceProperties binance,
                       String apiKey,
                       String privateCredential,
                       Clock clock,
                       long serverTimeOffsetMillis,
                       BinanceHttpTransport transport,
                       ObjectMapper jsonMapper) {
        this(binance, apiKey, privateCredential, clock, serverTimeOffsetMillis, transport, jsonMapper, new BinanceRateLimitTracker(clock));
    }

    BinanceOrderClient(BinanceProperties binance,
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
        this.requestFactory = new BinanceOrderRequestFactory(binance, clock, serverTimeOffsetMillis);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.rateLimitTracker = Objects.requireNonNull(rateLimitTracker, "rateLimitTracker");
    }

    BinanceOrderResult placeOrder(BinanceOrderCommand command) {
        return parseOrderResult(send(requestFactory.newOrder(command, privateCredential), "POST"));
    }

    BinanceOrderResult cancelOrder(String symbol, String originalClientOrderId) {
        return parseOrderResult(send(requestFactory.cancelOrder(symbol, originalClientOrderId, privateCredential), "DELETE"));
    }

    BinanceOrderResult queryOrder(String symbol, String originalClientOrderId) {
        return parseOrderResult(send(requestFactory.queryOrder(symbol, originalClientOrderId, privateCredential), "GET"));
    }

    List<BinanceOrderResult> openOrders(String symbol) {
        JsonNode root = readJson(send(requestFactory.openOrders(symbol, privateCredential), "GET"));
        if (!root.isArray()) {
            throw new IllegalStateException("Expected Binance open orders array response");
        }

        List<BinanceOrderResult> orders = new ArrayList<>();
        for (JsonNode item : root) {
            orders.add(toOrderResult(item));
        }
        return List.copyOf(orders);
    }

    List<BinanceOrderResult> allOrders(BinanceOrderHistoryQuery query) {
        return parseOrderArray(send(requestFactory.allOrders(query, privateCredential), "GET"), "all orders");
    }

    List<BinanceAccountTrade> accountTrades(BinanceTradeHistoryQuery query) {
        JsonNode root = readJson(send(requestFactory.accountTrades(query, privateCredential), "GET"));
        if (!root.isArray()) {
            throw new IllegalStateException("Expected Binance account trades array response");
        }

        List<BinanceAccountTrade> trades = new ArrayList<>();
        for (JsonNode item : root) {
            trades.add(toAccountTrade(item));
        }
        return List.copyOf(trades);
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

    private BinanceOrderResult parseOrderResult(BinanceHttpResponse response) {
        return toOrderResult(readJson(response));
    }

    private JsonNode readJson(BinanceHttpResponse response) {
        return jsonMapper.readTree(response.body());
    }

    private List<BinanceOrderResult> parseOrderArray(BinanceHttpResponse response, String responseName) {
        JsonNode root = readJson(response);
        if (!root.isArray()) {
            throw new IllegalStateException("Expected Binance " + responseName + " array response");
        }

        List<BinanceOrderResult> orders = new ArrayList<>();
        for (JsonNode item : root) {
            orders.add(toOrderResult(item));
        }
        return List.copyOf(orders);
    }

    private BinanceOrderResult toOrderResult(JsonNode node) {
        return new BinanceOrderResult(
                text(node, "symbol"),
                longValue(node, "orderId"),
                clientOrderId(node),
                text(node, "status"),
                text(node, "side"),
                text(node, "type"),
                text(node, "positionSide"),
                decimal(node, "price"),
                decimal(node, "origQty"),
                decimal(node, "executedQty"),
                decimal(node, "avgPrice"),
                firstDecimal(node, "cumQuote", "cummulativeQuoteQty", "cumBase"),
                longValue(node, "updateTime")
        );
    }

    private BinanceAccountTrade toAccountTrade(JsonNode node) {
        return new BinanceAccountTrade(
                text(node, "symbol"),
                longValue(node, "id"),
                longValue(node, "orderId"),
                longValue(node, "orderListId"),
                text(node, "pair"),
                text(node, "side"),
                text(node, "positionSide"),
                decimal(node, "price"),
                decimal(node, "qty"),
                decimal(node, "quoteQty"),
                decimal(node, "baseQty"),
                decimal(node, "realizedPnl"),
                decimal(node, "commission"),
                text(node, "commissionAsset"),
                text(node, "marginAsset"),
                firstBoolean(node, "buyer", "isBuyer").orElse(null),
                firstBoolean(node, "maker", "isMaker").orElse(null),
                firstBoolean(node, "isBestMatch").orElse(null),
                longValue(node, "time")
        );
    }

    private String clientOrderId(JsonNode node) {
        String clientOrderId = text(node, "clientOrderId");
        return clientOrderId == null ? text(node, "origClientOrderId") : clientOrderId;
    }

    private String text(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        String value = node.required(field).asString();
        return value.isBlank() ? null : value;
    }

    private Long longValue(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.required(field).asLong() : null;
    }

    private BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : new BigDecimal(value);
    }

    private BigDecimal firstDecimal(JsonNode node, String... fields) {
        for (String field : fields) {
            BigDecimal value = decimal(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Optional<Boolean> firstBoolean(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.hasNonNull(field)) {
                return Optional.of(node.required(field).asBoolean());
            }
        }
        return Optional.empty();
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
