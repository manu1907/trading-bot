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

    List<BinanceBatchOrderResult> placeBatchOrders(List<BinanceOrderCommand> commands) {
        return parseBatchOrderResults(send(requestFactory.batchOrders(commands, privateCredential), "POST"), "batch orders");
    }

    BinanceOrderResult modifyOrder(BinanceModifyOrderCommand command) {
        return parseOrderResult(send(requestFactory.modifyOrder(command, privateCredential), "PUT"));
    }

    List<BinanceBatchOrderResult> modifyMultipleOrders(List<BinanceModifyOrderCommand> commands) {
        return parseBatchOrderResults(send(requestFactory.modifyMultipleOrders(commands, privateCredential), "PUT"), "modify multiple orders");
    }

    List<BinanceOrderAmendment> modifyOrderHistory(BinanceModifyOrderHistoryQuery query) {
        JsonNode root = readJson(send(requestFactory.modifyOrderHistory(query, privateCredential), "GET"));
        if (!root.isArray()) {
            throw new IllegalStateException("Expected Binance modify order history array response");
        }

        List<BinanceOrderAmendment> amendments = new ArrayList<>();
        for (JsonNode item : root) {
            amendments.add(toOrderAmendment(item));
        }
        return List.copyOf(amendments);
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

    BinanceSpotCommissionRates commissionRates(String symbol) {
        return toCommissionRates(readJson(send(requestFactory.commissionRates(symbol, privateCredential), "GET")));
    }

    List<BinancePreventedMatch> preventedMatches(BinancePreventedMatchesQuery query) {
        JsonNode root = readJson(send(requestFactory.preventedMatches(query, privateCredential), "GET"));
        if (!root.isArray()) {
            throw new IllegalStateException("Expected Binance prevented matches array response");
        }

        List<BinancePreventedMatch> matches = new ArrayList<>();
        for (JsonNode item : root) {
            matches.add(toPreventedMatch(item));
        }
        return List.copyOf(matches);
    }

    BinanceAmendKeepPriorityResult amendKeepPriority(BinanceAmendKeepPriorityCommand command) {
        return toAmendKeepPriorityResult(readJson(send(requestFactory.amendKeepPriority(command, privateCredential), "PUT")));
    }

    BinanceCancelReplaceResult cancelReplace(BinanceCancelReplaceCommand command) {
        BinanceHttpResponse response = sendRaw(requestFactory.cancelReplace(command, privateCredential), "POST");
        JsonNode root = readJson(response);
        if ((response.statusCode() >= 200 && response.statusCode() <= 299) || root.hasNonNull("data")) {
            return toCancelReplaceResult(root, response.statusCode());
        }
        throw toApiException(response);
    }

    BinanceSorOrderResult placeSorOrder(BinanceOrderCommand command) {
        return toSorOrderResult(readJson(send(requestFactory.sorOrder(command, privateCredential), "POST")));
    }

    BinanceSorTestOrderResult testSorOrder(BinanceOrderCommand command, boolean computeCommissionRates) {
        JsonNode root = readJson(send(requestFactory.sorTestOrder(command, computeCommissionRates, privateCredential), "POST"));
        return toSorTestOrderResult(root);
    }

    BinanceOrderListResult placeOcoOrderList(BinanceOcoOrderListCommand command) {
        return toOrderListResult(readJson(send(requestFactory.ocoOrderList(command, privateCredential), "POST")));
    }

    BinanceOrderListResult placeOtoOrderList(BinanceOtoOrderListCommand command) {
        return toOrderListResult(readJson(send(requestFactory.otoOrderList(command, privateCredential), "POST")));
    }

    BinanceOrderListResult placeOtocoOrderList(BinanceOtocoOrderListCommand command) {
        return toOrderListResult(readJson(send(requestFactory.otocoOrderList(command, privateCredential), "POST")));
    }

    BinanceOrderListResult placeOpoOrderList(BinanceOpoOrderListCommand command) {
        return toOrderListResult(readJson(send(requestFactory.opoOrderList(command, privateCredential), "POST")));
    }

    BinanceOrderListResult placeOpocoOrderList(BinanceOpocoOrderListCommand command) {
        return toOrderListResult(readJson(send(requestFactory.opocoOrderList(command, privateCredential), "POST")));
    }

    BinanceOrderAck cancelAllOpenOrders(String symbol) {
        JsonNode root = readJson(send(requestFactory.cancelAllOpenOrders(symbol, privateCredential), "DELETE"));
        return new BinanceOrderAck(root.required("code").asInt(), text(root, "msg"));
    }

    List<BinanceOrderResult> cancelMultipleOrders(BinanceCancelMultipleOrdersQuery query) {
        return parseOrderArray(send(requestFactory.cancelMultipleOrders(query, privateCredential), "DELETE"), "cancel multiple orders");
    }

    BinanceCountdownCancelAll countdownCancelAll(String symbol, long countdownTime) {
        JsonNode root = readJson(send(requestFactory.countdownCancelAll(symbol, countdownTime, privateCredential), "POST"));
        return new BinanceCountdownCancelAll(text(root, "symbol"), longValue(root, "countdownTime"));
    }

    Optional<BinanceRateLimitUsage> currentRateLimitUsage() {
        return rateLimitTracker.current();
    }

    private BinanceHttpResponse send(BinanceSignedRequest request, String method) {
        BinanceHttpResponse response = sendRaw(request, method);
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw toApiException(response);
        }
        return response;
    }

    private BinanceHttpResponse sendRaw(BinanceSignedRequest request, String method) {
        try {
            BinanceHttpResponse response = transport.send(request, method, apiKey, binance.rest().apiKeyHeader());
            rateLimitTracker.observe(binance.rest(), response);
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

    private List<BinanceBatchOrderResult> parseBatchOrderResults(BinanceHttpResponse response, String responseName) {
        JsonNode root = readJson(response);
        if (!root.isArray()) {
            throw new IllegalStateException("Expected Binance " + responseName + " array response");
        }

        List<BinanceBatchOrderResult> results = new ArrayList<>();
        for (JsonNode item : root) {
            if (item.hasNonNull("code") && item.hasNonNull("msg")) {
                results.add(new BinanceBatchOrderResult(null, item.required("code").asInt(), text(item, "msg")));
            } else {
                results.add(new BinanceBatchOrderResult(toOrderResult(item), null, null));
            }
        }
        return List.copyOf(results);
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

    private BinanceOrderAmendment toOrderAmendment(JsonNode node) {
        JsonNode amendment = node.hasNonNull("amendment") ? node.required("amendment") : jsonMapper.createObjectNode();
        JsonNode price = amendment.hasNonNull("price") ? amendment.required("price") : jsonMapper.createObjectNode();
        JsonNode originalQuantity = amendment.hasNonNull("origQty") ? amendment.required("origQty") : jsonMapper.createObjectNode();
        return new BinanceOrderAmendment(
                longValue(node, "amendmentId"),
                text(node, "symbol"),
                text(node, "pair"),
                longValue(node, "orderId"),
                text(node, "clientOrderId"),
                longValue(node, "time"),
                decimal(price, "before"),
                decimal(price, "after"),
                decimal(originalQuantity, "before"),
                decimal(originalQuantity, "after"),
                amendment.hasNonNull("count") ? amendment.required("count").asInt() : null
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

    private BinanceSpotCommissionRates toCommissionRates(JsonNode node) {
        return new BinanceSpotCommissionRates(
                text(node, "symbol"),
                toCommissionRateSet(node, "standardCommission"),
                toCommissionRateSet(node, "specialCommission"),
                toCommissionRateSet(node, "taxCommission"),
                toCommissionDiscount(node)
        );
    }

    private BinancePreventedMatch toPreventedMatch(JsonNode node) {
        return new BinancePreventedMatch(
                text(node, "symbol"),
                longValue(node, "preventedMatchId"),
                longValue(node, "takerOrderId"),
                text(node, "makerSymbol"),
                longValue(node, "makerOrderId"),
                longValue(node, "tradeGroupId"),
                text(node, "selfTradePreventionMode"),
                decimal(node, "price"),
                decimal(node, "makerPreventedQuantity"),
                longValue(node, "transactTime")
        );
    }

    private BinanceAmendKeepPriorityResult toAmendKeepPriorityResult(JsonNode node) {
        return new BinanceAmendKeepPriorityResult(
                longValue(node, "transactTime"),
                longValue(node, "executionId"),
                toAmendedOrder(node.required("amendedOrder"))
        );
    }

    private BinanceAmendedOrder toAmendedOrder(JsonNode node) {
        return new BinanceAmendedOrder(
                text(node, "symbol"),
                longValue(node, "orderId"),
                longValue(node, "orderListId"),
                text(node, "origClientOrderId"),
                text(node, "clientOrderId"),
                decimal(node, "price"),
                decimal(node, "qty"),
                decimal(node, "executedQty"),
                decimal(node, "preventedQty"),
                firstDecimal(node, "cumulativeQuoteQty", "cummulativeQuoteQty"),
                text(node, "status"),
                text(node, "timeInForce"),
                text(node, "type"),
                text(node, "side"),
                longValue(node, "workingTime"),
                text(node, "selfTradePreventionMode")
        );
    }

    private BinanceCancelReplaceResult toCancelReplaceResult(JsonNode root, int httpStatusCode) {
        JsonNode data = root.hasNonNull("data") ? root.required("data") : root;
        return new BinanceCancelReplaceResult(
                httpStatusCode,
                root.hasNonNull("code") ? root.required("code").asInt() : null,
                text(root, "msg"),
                text(data, "cancelResult"),
                text(data, "newOrderResult"),
                toOptionalOrderResult(data, "cancelResponse"),
                toOptionalOrderResult(data, "newOrderResponse"),
                toOptionalError(data, "cancelResponse"),
                toOptionalError(data, "newOrderResponse")
        );
    }

    private BinanceOrderResult toOptionalOrderResult(JsonNode node, String field) {
        JsonNode child = node.hasNonNull(field) ? node.required(field) : null;
        if (child == null || child.hasNonNull("code")) {
            return null;
        }
        return toOrderResult(child);
    }

    private BinanceCancelReplaceError toOptionalError(JsonNode node, String field) {
        JsonNode child = node.hasNonNull(field) ? node.required(field) : null;
        if (child == null || !child.hasNonNull("code")) {
            return null;
        }
        return new BinanceCancelReplaceError(child.required("code").asInt(), text(child, "msg"));
    }

    private BinanceSorOrderResult toSorOrderResult(JsonNode node) {
        return new BinanceSorOrderResult(
                text(node, "symbol"),
                longValue(node, "orderId"),
                longValue(node, "orderListId"),
                text(node, "clientOrderId"),
                longValue(node, "transactTime"),
                decimal(node, "price"),
                decimal(node, "origQty"),
                decimal(node, "executedQty"),
                decimal(node, "origQuoteOrderQty"),
                firstDecimal(node, "cummulativeQuoteQty", "cumulativeQuoteQty"),
                text(node, "status"),
                text(node, "timeInForce"),
                text(node, "type"),
                text(node, "side"),
                longValue(node, "workingTime"),
                node.hasNonNull("fills") ? sorFills(node.required("fills")) : List.of(),
                text(node, "workingFloor"),
                text(node, "selfTradePreventionMode"),
                node.hasNonNull("usedSor") ? node.required("usedSor").asBoolean() : null
        );
    }

    private List<BinanceSorFill> sorFills(JsonNode fillsNode) {
        if (!fillsNode.isArray()) {
            throw new IllegalStateException("Expected Binance SOR fills array response");
        }
        List<BinanceSorFill> fills = new ArrayList<>();
        for (JsonNode fill : fillsNode) {
            fills.add(new BinanceSorFill(
                    text(fill, "matchType"),
                    decimal(fill, "price"),
                    decimal(fill, "qty"),
                    decimal(fill, "commission"),
                    text(fill, "commissionAsset"),
                    longValue(fill, "tradeId"),
                    longValue(fill, "allocId")
            ));
        }
        return List.copyOf(fills);
    }

    private BinanceSorTestOrderResult toSorTestOrderResult(JsonNode node) {
        return new BinanceSorTestOrderResult(
                node.hasNonNull("standardCommissionForOrder") ? toCommissionRateSet(node, "standardCommissionForOrder") : null,
                node.hasNonNull("taxCommissionForOrder") ? toCommissionRateSet(node, "taxCommissionForOrder") : null,
                node.hasNonNull("discount") ? toCommissionDiscount(node) : null
        );
    }

    private BinanceOrderListResult toOrderListResult(JsonNode node) {
        return new BinanceOrderListResult(
                longValue(node, "orderListId"),
                text(node, "contingencyType"),
                text(node, "listStatusType"),
                text(node, "listOrderStatus"),
                text(node, "listClientOrderId"),
                longValue(node, "transactionTime"),
                text(node, "symbol"),
                toOrderListOrders(node.hasNonNull("orders") ? node.required("orders") : jsonMapper.createArrayNode()),
                toOrderListReports(node.hasNonNull("orderReports") ? node.required("orderReports") : jsonMapper.createArrayNode())
        );
    }

    private List<BinanceOrderListOrder> toOrderListOrders(JsonNode ordersNode) {
        if (!ordersNode.isArray()) {
            throw new IllegalStateException("Expected Binance order-list orders array response");
        }
        List<BinanceOrderListOrder> orders = new ArrayList<>();
        for (JsonNode order : ordersNode) {
            orders.add(new BinanceOrderListOrder(
                    text(order, "symbol"),
                    longValue(order, "orderId"),
                    text(order, "clientOrderId")
            ));
        }
        return List.copyOf(orders);
    }

    private List<BinanceOrderListReport> toOrderListReports(JsonNode reportsNode) {
        if (!reportsNode.isArray()) {
            throw new IllegalStateException("Expected Binance order-list reports array response");
        }
        List<BinanceOrderListReport> reports = new ArrayList<>();
        for (JsonNode report : reportsNode) {
            reports.add(new BinanceOrderListReport(
                    text(report, "symbol"),
                    longValue(report, "orderId"),
                    longValue(report, "orderListId"),
                    text(report, "clientOrderId"),
                    longValue(report, "transactTime"),
                    decimal(report, "price"),
                    decimal(report, "origQty"),
                    decimal(report, "executedQty"),
                    decimal(report, "origQuoteOrderQty"),
                    firstDecimal(report, "cummulativeQuoteQty", "cumulativeQuoteQty"),
                    text(report, "status"),
                    text(report, "timeInForce"),
                    text(report, "type"),
                    text(report, "side"),
                    decimal(report, "stopPrice"),
                    decimal(report, "icebergQty"),
                    longValue(report, "workingTime"),
                    text(report, "selfTradePreventionMode")
            ));
        }
        return List.copyOf(reports);
    }

    private BinanceCommissionRateSet toCommissionRateSet(JsonNode node, String field) {
        JsonNode rates = node.required(field);
        return new BinanceCommissionRateSet(
                decimal(rates, "maker"),
                decimal(rates, "taker"),
                decimal(rates, "buyer"),
                decimal(rates, "seller")
        );
    }

    private BinanceCommissionDiscount toCommissionDiscount(JsonNode node) {
        JsonNode discount = node.required("discount");
        return new BinanceCommissionDiscount(
                discount.required("enabledForAccount").asBoolean(),
                discount.required("enabledForSymbol").asBoolean(),
                text(discount, "discountAsset"),
                decimal(discount, "discount")
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
