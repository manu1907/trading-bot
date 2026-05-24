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

final class BinanceMarginAccountClient {

    private final BinanceProperties binance;
    private final String apiKey;
    private final String privateCredential;
    private final BinanceMarginAccountRequestFactory requestFactory;
    private final BinanceHttpTransport transport;
    private final ObjectMapper jsonMapper;
    private final BinanceRateLimitTracker rateLimitTracker;

    BinanceMarginAccountClient(BinanceProperties binance,
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

    BinanceMarginAccountClient(BinanceProperties binance,
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
        this.requestFactory = new BinanceMarginAccountRequestFactory(binance, clock, serverTimeOffsetMillis);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.rateLimitTracker = Objects.requireNonNull(rateLimitTracker, "rateLimitTracker");
    }

    BinanceMarginBorrowRepayResult borrowRepay(BinanceMarginBorrowRepayCommand command) {
        JsonNode root = readJson(send(requestFactory.borrowRepay(command, privateCredential), "POST"));
        return new BinanceMarginBorrowRepayResult(root.required("tranId").asLong());
    }

    BinanceMarginTransferHistoryPage transferHistory(BinanceMarginTransferHistoryQuery query) {
        JsonNode root = readJson(send(requestFactory.transferHistory(query, privateCredential), "GET"));
        List<BinanceMarginTransferRecord> rows = new ArrayList<>();
        JsonNode rowsNode = root.required("rows");
        for (JsonNode item : rowsNode) {
            rows.add(toTransferRecord(item));
        }
        return new BinanceMarginTransferHistoryPage(rows, root.required("total").asLong());
    }

    BinanceMarginMaxTransferable maxTransferable(String asset, String isolatedSymbol) {
        JsonNode root = readJson(send(requestFactory.maxTransferable(asset, isolatedSymbol, privateCredential), "GET"));
        return new BinanceMarginMaxTransferable(decimal(root, "amount"));
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

    private JsonNode readJson(BinanceHttpResponse response) {
        return jsonMapper.readTree(response.body());
    }

    private BinanceMarginTransferRecord toTransferRecord(JsonNode node) {
        return new BinanceMarginTransferRecord(
                decimal(node, "amount"),
                text(node, "asset"),
                text(node, "status"),
                longValue(node, "timestamp"),
                longValue(node, "txId"),
                text(node, "type"),
                text(node, "transFrom"),
                text(node, "transTo"),
                text(node, "fromSymbol"),
                text(node, "toSymbol")
        );
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

    private Long longValue(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.required(field).asLong() : null;
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
