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

    BinanceCrossMarginAccountSnapshot crossAccount() {
        JsonNode root = readJson(send(requestFactory.crossAccount(privateCredential), "GET"));
        List<BinanceMarginAssetBalance> userAssets = new ArrayList<>();
        JsonNode assetsNode = root.required("userAssets");
        for (JsonNode item : assetsNode) {
            userAssets.add(toMarginAssetBalance(item));
        }
        return new BinanceCrossMarginAccountSnapshot(
                optionalBoolean(root, "created").orElse(null),
                optionalBoolean(root, "borrowEnabled").orElse(null),
                decimal(root, "marginLevel"),
                decimal(root, "collateralMarginLevel"),
                decimal(root, "totalAssetOfBtc"),
                decimal(root, "totalLiabilityOfBtc"),
                decimal(root, "totalNetAssetOfBtc"),
                decimal(root, "TotalCollateralValueInUSDT"),
                decimal(root, "totalOpenOrderLossInUSDT"),
                optionalBoolean(root, "tradeEnabled").orElse(null),
                optionalBoolean(root, "transferInEnabled").orElse(null),
                optionalBoolean(root, "transferOutEnabled").orElse(null),
                text(root, "accountType"),
                userAssets
        );
    }

    BinanceIsolatedMarginAccountSnapshot isolatedAccount(BinanceIsolatedMarginAccountQuery query) {
        JsonNode root = readJson(send(requestFactory.isolatedAccount(query, privateCredential), "GET"));
        List<BinanceIsolatedMarginPairSnapshot> assets = new ArrayList<>();
        JsonNode assetsNode = root.required("assets");
        for (JsonNode item : assetsNode) {
            assets.add(toIsolatedMarginPairSnapshot(item));
        }
        return new BinanceIsolatedMarginAccountSnapshot(
                assets,
                decimal(root, "totalAssetOfBtc"),
                decimal(root, "totalLiabilityOfBtc"),
                decimal(root, "totalNetAssetOfBtc")
        );
    }

    BinanceIsolatedMarginAccountLimit isolatedAccountLimit() {
        JsonNode root = readJson(send(requestFactory.isolatedAccountLimit(privateCredential), "GET"));
        return new BinanceIsolatedMarginAccountLimit(integer(root, "enabledAccount"), integer(root, "maxAccount"));
    }

    BinanceMarginTradeCoeff tradeCoeff() {
        JsonNode root = readJson(send(requestFactory.tradeCoeff(privateCredential), "GET"));
        return new BinanceMarginTradeCoeff(
                decimal(root, "normalBar"),
                decimal(root, "marginCallBar"),
                decimal(root, "forceLiquidationBar")
        );
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

    private BinanceMarginAssetBalance toMarginAssetBalance(JsonNode node) {
        return new BinanceMarginAssetBalance(
                text(node, "asset"),
                decimal(node, "borrowed"),
                decimal(node, "free"),
                decimal(node, "interest"),
                decimal(node, "locked"),
                decimal(node, "netAsset")
        );
    }

    private BinanceIsolatedMarginPairSnapshot toIsolatedMarginPairSnapshot(JsonNode node) {
        return new BinanceIsolatedMarginPairSnapshot(
                toIsolatedMarginAssetBalance(node.required("baseAsset")),
                toIsolatedMarginAssetBalance(node.required("quoteAsset")),
                text(node, "symbol"),
                optionalBoolean(node, "isolatedCreated").orElse(null),
                optionalBoolean(node, "enabled").orElse(null),
                decimal(node, "marginLevel"),
                text(node, "marginLevelStatus"),
                decimal(node, "marginRatio"),
                decimal(node, "indexPrice"),
                decimal(node, "liquidatePrice"),
                decimal(node, "liquidateRate"),
                optionalBoolean(node, "tradeEnabled").orElse(null)
        );
    }

    private BinanceIsolatedMarginAssetBalance toIsolatedMarginAssetBalance(JsonNode node) {
        return new BinanceIsolatedMarginAssetBalance(
                text(node, "asset"),
                optionalBoolean(node, "borrowEnabled").orElse(null),
                decimal(node, "borrowed"),
                decimal(node, "free"),
                decimal(node, "interest"),
                decimal(node, "locked"),
                decimal(node, "netAsset"),
                decimal(node, "netAssetOfBtc"),
                optionalBoolean(node, "repayEnabled").orElse(null),
                decimal(node, "totalAsset")
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

    private Integer integer(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.required(field).asInt() : null;
    }

    private Optional<Boolean> optionalBoolean(JsonNode node, String field) {
        return node.hasNonNull(field) ? Optional.of(node.required(field).asBoolean()) : Optional.empty();
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
