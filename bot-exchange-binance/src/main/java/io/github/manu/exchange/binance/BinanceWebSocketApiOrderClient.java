package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class BinanceWebSocketApiOrderClient {

    private final BinanceProperties binance;
    private final String apiKey;
    private final String privateCredential;
    private final BinanceWebSocketApiRequestFactory requestFactory;
    private final BinanceWebSocketEndpointPlanner endpointPlanner;
    private final BinanceWebSocketClient webSocketClient;
    private final ObjectMapper jsonMapper;
    private final Duration requestTimeout;
    private final Optional<BinanceExchangeMetadata> exchangeMetadata;
    private final Optional<BinanceReferencePriceProvider> referencePriceProvider;

    BinanceWebSocketApiOrderClient(BinanceProperties binance,
                                   String apiKey,
                                   String privateCredential,
                                   Clock clock,
                                   long serverTimeOffsetMillis,
                                   BinanceWebSocketTransport transport,
                                   Duration requestTimeout) {
        this(
                binance,
                apiKey,
                privateCredential,
                clock,
                serverTimeOffsetMillis,
                transport,
                JsonMapperFactory.create(),
                requestTimeout,
                null,
                null
        );
    }

    BinanceWebSocketApiOrderClient(BinanceProperties binance,
                                   String apiKey,
                                   String privateCredential,
                                   Clock clock,
                                   long serverTimeOffsetMillis,
                                   BinanceWebSocketTransport transport,
                                   ObjectMapper jsonMapper,
                                   Duration requestTimeout) {
        this(
                binance,
                apiKey,
                privateCredential,
                clock,
                serverTimeOffsetMillis,
                transport,
                jsonMapper,
                requestTimeout,
                null,
                null
        );
    }

    BinanceWebSocketApiOrderClient(BinanceProperties binance,
                                   String apiKey,
                                   String privateCredential,
                                   Clock clock,
                                   long serverTimeOffsetMillis,
                                   BinanceWebSocketTransport transport,
                                   ObjectMapper jsonMapper,
                                   Duration requestTimeout,
                                   BinanceExchangeMetadata exchangeMetadata,
                                   BinanceReferencePriceProvider referencePriceProvider) {
        this.binance = Objects.requireNonNull(binance, "binance");
        this.apiKey = requireText(apiKey, "apiKey");
        this.privateCredential = requireText(privateCredential, "privateCredential");
        this.requestFactory = new BinanceWebSocketApiRequestFactory(binance, clock, serverTimeOffsetMillis, jsonMapper);
        this.endpointPlanner = new BinanceWebSocketEndpointPlanner(binance.websocket(), clock);
        this.webSocketClient = new BinanceWebSocketClient(transport);
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.exchangeMetadata = Optional.ofNullable(exchangeMetadata);
        this.referencePriceProvider = Optional.ofNullable(referencePriceProvider);
    }

    BinanceOrderResult placeOrder(String requestId, BinanceOrderCommand command) {
        validateExchangeFilters(command);
        BinanceWebSocketApiRequest request = requestFactory.placeOrder(requestId, command, apiKey, privateCredential);
        ResponseListener listener = new ResponseListener(request.id(), jsonMapper);
        try (BinanceWebSocketConnection connection = webSocketClient.connect(endpointPlanner.api(), listener)) {
            connection.sendText(request.payload());
            return listener.result(requestTimeout);
        }
    }

    private void validateExchangeFilters(BinanceOrderCommand command) {
        if (!binance.trading().enforceExchangeFilters()) {
            return;
        }
        BinanceExchangeMetadata metadata = exchangeMetadata.orElseThrow(() ->
                new IllegalArgumentException("exchangeInfo metadata is required for Binance exchange-filter validation"));
        new BinanceExchangeFilterValidator(
                binance.trading().enforcePercentPriceFilters(),
                referencePriceProvider.orElse(null)
        ).validate(command, metadata);
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
                firstLong(node, "updateTime", "transactTime")
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

    private Long firstLong(JsonNode node, String... fields) {
        for (String field : fields) {
            Long value = longValue(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
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

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private final class ResponseListener implements BinanceWebSocketListener {

        private final String requestId;
        private final ObjectMapper jsonMapper;
        private final CompletableFuture<BinanceOrderResult> result = new CompletableFuture<>();

        private ResponseListener(String requestId, ObjectMapper jsonMapper) {
            this.requestId = requestId;
            this.jsonMapper = jsonMapper;
        }

        @Override
        public void onText(String text) {
            JsonNode root = jsonMapper.readTree(text);
            if (!requestId.equals(text(root, "id"))) {
                return;
            }
            int status = root.required("status").asInt();
            if (status >= 200 && status <= 299) {
                result.complete(toOrderResult(root.required("result")));
                return;
            }
            JsonNode error = root.hasNonNull("error") ? root.required("error") : jsonMapper.createObjectNode();
            result.completeExceptionally(new BinanceApiException(
                    status,
                    error.hasNonNull("code") ? error.required("code").asInt() : null,
                    Optional.ofNullable(text(error, "msg")).orElse("WebSocket API status " + status)
            ));
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onClose() {
            result.completeExceptionally(new IllegalStateException("Binance websocket API connection closed before response"));
        }

        private BinanceOrderResult result(Duration timeout) {
            try {
                return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Binance websocket API response", e);
            } catch (TimeoutException e) {
                throw new IllegalStateException("Timed out waiting for Binance websocket API response", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Failed to process Binance websocket API response", cause);
            }
        }
    }
}
