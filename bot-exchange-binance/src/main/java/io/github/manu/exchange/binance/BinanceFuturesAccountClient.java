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
        return parseAck(send(requestFactory.changePositionMode(positionMode, privateCredential), "POST"));
    }

    BinanceFuturesAccountAck changeMarginType(String symbol, String marginType) {
        return parseAck(send(requestFactory.changeMarginType(symbol, marginType, privateCredential), "POST"));
    }

    BinanceFuturesLeverageResult changeInitialLeverage(String symbol, int leverage) {
        JsonNode root = readJson(send(requestFactory.changeInitialLeverage(symbol, leverage, privateCredential), "POST"));
        return new BinanceFuturesLeverageResult(
                text(root, "symbol"),
                root.required("leverage").asInt(),
                decimal(root, "maxNotionalValue"),
                decimal(root, "maxQty")
        );
    }

    BinanceFuturesAccountAck changeMultiAssetsMode(boolean multiAssetsMode) {
        return parseAck(send(requestFactory.changeMultiAssetsMode(multiAssetsMode, privateCredential), "POST"));
    }

    List<BinanceFuturesBalance> balances() {
        JsonNode root = readJson(send(requestFactory.balances(privateCredential), "GET"));
        if (!root.isArray()) {
            throw new IllegalStateException("Expected Binance futures balance array response");
        }
        List<BinanceFuturesBalance> balances = new ArrayList<>();
        for (JsonNode item : root) {
            balances.add(toBalance(item));
        }
        return List.copyOf(balances);
    }

    BinanceFuturesAccountSnapshot accountInfo() {
        JsonNode root = readJson(send(requestFactory.accountInfo(privateCredential), "GET"));
        return new BinanceFuturesAccountSnapshot(
                decimal(root, "totalInitialMargin"),
                decimal(root, "totalMaintMargin"),
                decimal(root, "totalWalletBalance"),
                decimal(root, "totalUnrealizedProfit"),
                decimal(root, "totalMarginBalance"),
                decimal(root, "totalPositionInitialMargin"),
                decimal(root, "totalOpenOrderInitialMargin"),
                decimal(root, "totalCrossWalletBalance"),
                firstDecimal(root, "totalCrossUnPnl", "totalCrossUnrealizedPnl"),
                decimal(root, "availableBalance"),
                decimal(root, "maxWithdrawAmount"),
                bool(root, "canDeposit").orElse(null),
                bool(root, "canTrade").orElse(null),
                bool(root, "canWithdraw").orElse(null),
                intValue(root, "feeTier"),
                longValue(root, "updateTime"),
                toAssets(root),
                toPositions(root)
        );
    }

    List<BinanceFuturesPositionSnapshot> positionRisk(BinanceFuturesPositionRiskQuery query) {
        JsonNode root = readJson(send(requestFactory.positionRisk(query, privateCredential), "GET"));
        if (!root.isArray()) {
            throw new IllegalStateException("Expected Binance futures position risk array response");
        }
        List<BinanceFuturesPositionSnapshot> positions = new ArrayList<>();
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

    private BinanceFuturesAccountAck parseAck(BinanceHttpResponse response) {
        JsonNode root = readJson(response);
        return new BinanceFuturesAccountAck(root.required("code").asInt(), text(root, "msg"));
    }

    private BinanceFuturesBalance toBalance(JsonNode node) {
        return new BinanceFuturesBalance(
                text(node, "accountAlias"),
                text(node, "asset"),
                decimal(node, "balance"),
                decimal(node, "crossWalletBalance"),
                firstDecimal(node, "crossUnPnl", "crossUnrealizedPnl"),
                decimal(node, "availableBalance"),
                decimal(node, "maxWithdrawAmount"),
                decimal(node, "withdrawAvailable"),
                bool(node, "marginAvailable").orElse(null),
                longValue(node, "updateTime")
        );
    }

    private List<BinanceFuturesAssetSnapshot> toAssets(JsonNode root) {
        JsonNode assetsNode = root.get("assets");
        if (assetsNode == null || !assetsNode.isArray()) {
            return List.of();
        }
        List<BinanceFuturesAssetSnapshot> assets = new ArrayList<>();
        for (JsonNode item : assetsNode) {
            assets.add(new BinanceFuturesAssetSnapshot(
                    text(item, "asset"),
                    decimal(item, "walletBalance"),
                    decimal(item, "unrealizedProfit"),
                    decimal(item, "marginBalance"),
                    decimal(item, "maintMargin"),
                    decimal(item, "initialMargin"),
                    decimal(item, "positionInitialMargin"),
                    decimal(item, "openOrderInitialMargin"),
                    decimal(item, "crossWalletBalance"),
                    firstDecimal(item, "crossUnPnl", "crossUnrealizedPnl"),
                    decimal(item, "availableBalance"),
                    decimal(item, "maxWithdrawAmount"),
                    longValue(item, "updateTime")
            ));
        }
        return List.copyOf(assets);
    }

    private List<BinanceFuturesPositionSnapshot> toPositions(JsonNode root) {
        JsonNode positionsNode = root.get("positions");
        if (positionsNode == null || !positionsNode.isArray()) {
            return List.of();
        }
        List<BinanceFuturesPositionSnapshot> positions = new ArrayList<>();
        for (JsonNode item : positionsNode) {
            positions.add(toPosition(item));
        }
        return List.copyOf(positions);
    }

    private BinanceFuturesPositionSnapshot toPosition(JsonNode node) {
        return new BinanceFuturesPositionSnapshot(
                text(node, "symbol"),
                text(node, "positionSide"),
                decimal(node, "positionAmt"),
                decimal(node, "entryPrice"),
                decimal(node, "breakEvenPrice"),
                decimal(node, "markPrice"),
                firstDecimal(node, "unRealizedProfit", "unrealizedProfit"),
                decimal(node, "liquidationPrice"),
                intValue(node, "leverage"),
                decimal(node, "maxQty"),
                text(node, "marginType"),
                bool(node, "isolated").orElse(null),
                bool(node, "isAutoAddMargin").orElse(null),
                decimal(node, "isolatedMargin"),
                decimal(node, "isolatedWallet"),
                firstDecimal(node, "notional", "notionalValue"),
                text(node, "marginAsset"),
                decimal(node, "initialMargin"),
                decimal(node, "maintMargin"),
                decimal(node, "positionInitialMargin"),
                decimal(node, "openOrderInitialMargin"),
                intValue(node, "adl"),
                decimal(node, "bidNotional"),
                decimal(node, "askNotional"),
                longValue(node, "updateTime")
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

    private BigDecimal firstDecimal(JsonNode node, String... fields) {
        for (String field : fields) {
            BigDecimal value = decimal(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
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
