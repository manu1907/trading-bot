package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class BinanceWebSocketApiRequestFactory {

    private static final String MILLISECONDS = "MILLISECONDS";
    private static final String MICROSECONDS = "MICROSECONDS";
    private static final String ORDER_PLACE = "order.place";

    private final BinanceProperties binance;
    private final Clock clock;
    private final long serverTimeOffsetMillis;
    private final ObjectMapper jsonMapper;

    BinanceWebSocketApiRequestFactory(BinanceProperties binance, Clock clock, long serverTimeOffsetMillis) {
        this(binance, clock, serverTimeOffsetMillis, JsonMapperFactory.create());
    }

    BinanceWebSocketApiRequestFactory(BinanceProperties binance,
                                      Clock clock,
                                      long serverTimeOffsetMillis,
                                      ObjectMapper jsonMapper) {
        this.binance = Objects.requireNonNull(binance, "binance");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.serverTimeOffsetMillis = serverTimeOffsetMillis;
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    BinanceWebSocketApiRequest placeOrder(String requestId,
                                          BinanceOrderCommand command,
                                          String apiKey,
                                          String privateCredential) {
        requireText(requestId, "requestId");
        requireText(apiKey, "apiKey");
        if (BinanceMarketType.fromConfigValue(binance.marketType()) != BinanceMarketType.SPOT) {
            throw new IllegalArgumentException("Binance websocket API order placement is only configured for Spot");
        }
        BinanceOrderCommandValidator.validate(command, binance.trading());

        Map<String, Object> params = orderParameters(command);
        params.put("apiKey", apiKey);
        params.put("recvWindow", binance.rest().recvWindowMillis());
        params.put("timestamp", timestamp());
        String signaturePayload = signaturePayload(params);
        String signature = BinanceRequestSigner.sign(signaturePayload, privateCredential, binance.rest().signatureAlgorithm());
        params.put("signature", signature);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", requestId);
        request.put("method", ORDER_PLACE);
        request.put("params", params);
        return new BinanceWebSocketApiRequest(
                requestId,
                ORDER_PLACE,
                jsonMapper.writeValueAsString(request),
                signaturePayload,
                signature
        );
    }

    private Map<String, Object> orderParameters(BinanceOrderCommand command) {
        Map<String, Object> params = new LinkedHashMap<>();
        add(params, "symbol", command.symbol());
        add(params, "side", command.side());
        add(params, "type", command.type());
        add(params, "timeInForce", command.timeInForce());
        add(params, "price", command.price());
        add(params, "quantity", command.quantity());
        add(params, "quoteOrderQty", command.quoteOrderQty());
        add(params, "newClientOrderId", command.clientOrderId());
        add(params, "newOrderRespType", command.orderResponseType());
        add(params, "stopPrice", command.stopPrice());
        add(params, "trailingDelta", command.trailingDelta());
        add(params, "icebergQty", command.icebergQty());
        add(params, "selfTradePreventionMode", command.selfTradePreventionMode());
        add(params, "pegPriceType", command.pegPriceType());
        add(params, "pegOffsetValue", command.pegOffsetValue());
        add(params, "pegOffsetType", command.pegOffsetType());
        return params;
    }

    private String signaturePayload(Map<String, Object> params) {
        return new TreeMap<>(params).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    private long timestamp() {
        Instant instant = clock.instant().plusMillis(serverTimeOffsetMillis);
        if (MILLISECONDS.equals(binance.websocket().timestampUnit())) {
            return instant.toEpochMilli();
        }
        if (MICROSECONDS.equals(binance.websocket().timestampUnit())) {
            return Math.addExact(
                    Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                    instant.getNano() / 1_000L
            );
        }
        throw new IllegalArgumentException("Unsupported Binance timestamp unit: " + binance.websocket().timestampUnit());
    }

    private void add(Map<String, Object> params, String name, String value) {
        if (value != null && !value.isBlank()) {
            params.put(name, value);
        }
    }

    private void add(Map<String, Object> params, String name, BigDecimal value) {
        if (value != null) {
            params.put(name, value.stripTrailingZeros().toPlainString());
        }
    }

    private void add(Map<String, Object> params, String name, Integer value) {
        if (value != null) {
            params.put(name, value);
        }
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
