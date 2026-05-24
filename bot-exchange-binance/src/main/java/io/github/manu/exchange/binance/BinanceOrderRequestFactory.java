package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.config.JsonMapperFactory;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

final class BinanceOrderRequestFactory {

    private final BinanceProperties binance;
    private final BinanceRestRequestFactory restRequestFactory;
    private final ObjectMapper jsonMapper = JsonMapperFactory.create();

    BinanceOrderRequestFactory(BinanceProperties binance, Clock clock, long serverTimeOffsetMillis) {
        this.binance = Objects.requireNonNull(binance, "binance");
        this.restRequestFactory = new BinanceRestRequestFactory(binance.rest(), clock, serverTimeOffsetMillis);
    }

    BinanceSignedRequest newOrder(BinanceOrderCommand command, String privateCredential) {
        BinanceOrderCommandValidator.validate(command, binance.trading());
        return restRequestFactory.signedUri(binance.trading().newOrderPath(), orderParameters(command), privateCredential);
    }

    BinanceSignedRequest batchOrders(List<BinanceOrderCommand> commands, String privateCredential) {
        requireConfiguredPath("batchOrdersPath", binance.trading().batchOrdersPath());
        validateBatchOrders(commands);
        return restRequestFactory.signedUri(binance.trading().batchOrdersPath(), List.of(
                BinanceRequestParameter.of("batchOrders", batchOrdersJson(commands))
        ), privateCredential);
    }

    BinanceSignedRequest modifyOrder(BinanceModifyOrderCommand command, String privateCredential) {
        requireConfiguredPath("modifyOrderPath", binance.trading().modifyOrderPath());
        validateModifyOrder(command);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", command.symbol());
        add(parameters, "orderId", command.orderId());
        add(parameters, "origClientOrderId", command.originalClientOrderId());
        add(parameters, "side", command.side());
        add(parameters, "quantity", command.quantity());
        add(parameters, "price", command.price());
        add(parameters, "priceMatch", command.priceMatch());
        return restRequestFactory.signedUri(binance.trading().modifyOrderPath(), parameters, privateCredential);
    }

    BinanceSignedRequest modifyMultipleOrders(List<BinanceModifyOrderCommand> commands, String privateCredential) {
        requireConfiguredPath("modifyMultipleOrdersPath", binance.trading().modifyMultipleOrdersPath());
        validateModifyMultipleOrders(commands);
        return restRequestFactory.signedUri(binance.trading().modifyMultipleOrdersPath(), List.of(
                BinanceRequestParameter.of("batchOrders", modifyOrdersJson(commands))
        ), privateCredential);
    }

    BinanceSignedRequest modifyOrderHistory(BinanceModifyOrderHistoryQuery query, String privateCredential) {
        requireConfiguredPath("modifyOrderHistoryPath", binance.trading().modifyOrderHistoryPath());
        validateModifyOrderHistoryQuery(query);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", query.symbol());
        add(parameters, "orderId", query.orderId());
        add(parameters, "origClientOrderId", query.originalClientOrderId());
        add(parameters, "startTime", query.startTime());
        add(parameters, "endTime", query.endTime());
        add(parameters, "limit", query.limit());
        return restRequestFactory.signedUri(binance.trading().modifyOrderHistoryPath(), parameters, privateCredential);
    }

    BinanceSignedRequest cancelOrder(String symbol, String originalClientOrderId, String privateCredential) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (originalClientOrderId == null || originalClientOrderId.isBlank()) {
            throw new IllegalArgumentException("origClientOrderId is required");
        }
        return restRequestFactory.signedUri(binance.trading().cancelOrderPath(), List.of(
                BinanceRequestParameter.of("symbol", symbol),
                BinanceRequestParameter.of("origClientOrderId", originalClientOrderId)
        ), privateCredential);
    }

    BinanceSignedRequest queryOrder(String symbol, String originalClientOrderId, String privateCredential) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (originalClientOrderId == null || originalClientOrderId.isBlank()) {
            throw new IllegalArgumentException("origClientOrderId is required");
        }
        return restRequestFactory.signedUri(binance.trading().queryOrderPath(), List.of(
                BinanceRequestParameter.of("symbol", symbol),
                BinanceRequestParameter.of("origClientOrderId", originalClientOrderId)
        ), privateCredential);
    }

    BinanceSignedRequest openOrders(String symbol, String privateCredential) {
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", symbol);
        return restRequestFactory.signedUri(binance.trading().openOrdersPath(), parameters, privateCredential);
    }

    BinanceSignedRequest allOrders(BinanceOrderHistoryQuery query, String privateCredential) {
        requireHistoryPath("allOrdersPath", binance.trading().allOrdersPath());
        validateOrderHistoryQuery(query);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", query.symbol());
        add(parameters, "pair", query.pair());
        add(parameters, "orderId", query.orderId());
        add(parameters, "startTime", query.startTime());
        add(parameters, "endTime", query.endTime());
        add(parameters, "limit", query.limit());
        addMarginIsolated(parameters, query.isolatedMargin());
        return restRequestFactory.signedUri(binance.trading().allOrdersPath(), parameters, privateCredential);
    }

    BinanceSignedRequest accountTrades(BinanceTradeHistoryQuery query, String privateCredential) {
        requireHistoryPath("accountTradesPath", binance.trading().accountTradesPath());
        validateTradeHistoryQuery(query);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", query.symbol());
        add(parameters, "pair", query.pair());
        add(parameters, "orderId", query.orderId());
        add(parameters, "startTime", query.startTime());
        add(parameters, "endTime", query.endTime());
        add(parameters, "fromId", query.fromId());
        add(parameters, "limit", query.limit());
        addMarginIsolated(parameters, query.isolatedMargin());
        return restRequestFactory.signedUri(binance.trading().accountTradesPath(), parameters, privateCredential);
    }

    BinanceSignedRequest commissionRates(String symbol, String privateCredential) {
        requireConfiguredPath("commissionRatesPath", binance.trading().commissionRatesPath());
        requireSymbol(symbol);
        return restRequestFactory.signedUri(binance.trading().commissionRatesPath(), List.of(
                BinanceRequestParameter.of("symbol", symbol)
        ), privateCredential);
    }

    BinanceSignedRequest preventedMatches(BinancePreventedMatchesQuery query, String privateCredential) {
        requireConfiguredPath("preventedMatchesPath", binance.trading().preventedMatchesPath());
        validatePreventedMatchesQuery(query);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", query.symbol());
        add(parameters, "preventedMatchId", query.preventedMatchId());
        add(parameters, "orderId", query.orderId());
        add(parameters, "fromPreventedMatchId", query.fromPreventedMatchId());
        add(parameters, "limit", query.limit());
        return restRequestFactory.signedUri(binance.trading().preventedMatchesPath(), parameters, privateCredential);
    }

    BinanceSignedRequest amendKeepPriority(BinanceAmendKeepPriorityCommand command, String privateCredential) {
        requireConfiguredPath("amendKeepPriorityPath", binance.trading().amendKeepPriorityPath());
        validateAmendKeepPriority(command);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", command.symbol());
        add(parameters, "orderId", command.orderId());
        add(parameters, "origClientOrderId", command.originalClientOrderId());
        add(parameters, "newClientOrderId", command.newClientOrderId());
        add(parameters, "newQty", command.newQuantity());
        return restRequestFactory.signedUri(binance.trading().amendKeepPriorityPath(), parameters, privateCredential);
    }

    BinanceSignedRequest cancelAllOpenOrders(String symbol, String privateCredential) {
        requireConfiguredPath("cancelAllOpenOrdersPath", binance.trading().cancelAllOpenOrdersPath());
        requireSymbol(symbol);
        return restRequestFactory.signedUri(binance.trading().cancelAllOpenOrdersPath(), List.of(
                BinanceRequestParameter.of("symbol", symbol)
        ), privateCredential);
    }

    BinanceSignedRequest cancelMultipleOrders(BinanceCancelMultipleOrdersQuery query, String privateCredential) {
        requireConfiguredPath("cancelMultipleOrdersPath", binance.trading().cancelMultipleOrdersPath());
        validateCancelMultipleOrdersQuery(query);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", query.symbol());
        if (!query.orderIds().isEmpty()) {
            add(parameters, "orderIdList", longList(query.orderIds()));
        }
        if (!query.originalClientOrderIds().isEmpty()) {
            add(parameters, "origClientOrderIdList", stringList(query.originalClientOrderIds()));
        }
        return restRequestFactory.signedUri(binance.trading().cancelMultipleOrdersPath(), parameters, privateCredential);
    }

    BinanceSignedRequest countdownCancelAll(String symbol, long countdownTime, String privateCredential) {
        requireConfiguredPath("countdownCancelAllPath", binance.trading().countdownCancelAllPath());
        requireSymbol(symbol);
        if (countdownTime < 0) {
            throw new IllegalArgumentException("countdownTime must be zero or positive");
        }
        return restRequestFactory.signedUri(binance.trading().countdownCancelAllPath(), List.of(
                BinanceRequestParameter.of("symbol", symbol),
                BinanceRequestParameter.of("countdownTime", Long.toString(countdownTime))
        ), privateCredential);
    }

    private List<BinanceRequestParameter> orderParameters(BinanceOrderCommand command) {
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", command.symbol());
        add(parameters, "side", command.side());
        add(parameters, "type", command.type());
        add(parameters, "timeInForce", command.timeInForce());
        add(parameters, "positionSide", command.positionSide());
        add(parameters, "newOrderRespType", command.orderResponseType());
        add(parameters, "selfTradePreventionMode", command.selfTradePreventionMode());
        add(parameters, "sideEffectType", command.sideEffectType());
        add(parameters, "priceMatch", command.priceMatch());
        add(parameters, "workingType", command.workingType());
        add(parameters, "pegPriceType", command.pegPriceType());
        add(parameters, "pegOffsetType", command.pegOffsetType());
        add(parameters, "pegOffsetValue", command.pegOffsetValue());
        add(parameters, "newClientOrderId", command.clientOrderId());
        add(parameters, "goodTillDate", command.goodTillDate());
        add(parameters, "quantity", command.quantity());
        add(parameters, "quoteOrderQty", command.quoteOrderQty());
        add(parameters, "price", command.price());
        add(parameters, "stopPrice", command.stopPrice());
        add(parameters, "trailingDelta", command.trailingDelta());
        add(parameters, "callbackRate", command.callbackRate());
        add(parameters, "activationPrice", command.activationPrice());
        add(parameters, "icebergQty", command.icebergQty());
        add(parameters, "reduceOnly", command.reduceOnly());
        add(parameters, "closePosition", command.closePosition());
        addWhenPresent(parameters, "priceProtect", command.priceProtect());
        addWhenPresent(parameters, "autoRepayAtCancel", command.autoRepayAtCancel());
        add(parameters, "isIsolated", command.isolatedMargin());
        add(parameters, "marketMakerProtection", command.marketMakerProtection());
        return parameters;
    }

    private void validateBatchOrders(List<BinanceOrderCommand> commands) {
        Objects.requireNonNull(commands, "commands");
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("batchOrders requires at least one order");
        }
        if (commands.size() > 5) {
            throw new IllegalArgumentException("batchOrders can contain at most 5 orders");
        }
        for (BinanceOrderCommand command : commands) {
            BinanceOrderCommandValidator.validate(command, binance.trading());
        }
    }

    private String batchOrdersJson(List<BinanceOrderCommand> commands) {
        List<Map<String, String>> orders = commands.stream()
                .map(this::orderParameterMap)
                .toList();
        return jsonMapper.writeValueAsString(orders);
    }

    private Map<String, String> orderParameterMap(BinanceOrderCommand command) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (BinanceRequestParameter parameter : orderParameters(command)) {
            parameters.put(parameter.name(), parameter.value());
        }
        return parameters;
    }

    private void validateModifyOrder(BinanceModifyOrderCommand command) {
        Objects.requireNonNull(command, "command");
        BinanceMarketType marketType = BinanceMarketType.fromConfigValue(binance.marketType());
        requireSymbol(command.symbol());
        requirePositive("orderId", command.orderId());
        if (command.orderId() == null && !hasText(command.originalClientOrderId())) {
            throw new IllegalArgumentException("orderId or origClientOrderId is required");
        }
        if (!BinanceTradingCapability.forMarketType(marketType).supportedSides().contains(command.side())) {
            throw new IllegalArgumentException("side is not supported for this Binance market");
        }
        requirePositive("quantity", command.quantity());
        requirePositive("price", command.price());
        if (command.quantity() == null) {
            throw new IllegalArgumentException("quantity is required for futures modify order");
        }
        if (command.price() == null && !hasText(command.priceMatch())) {
            throw new IllegalArgumentException("price or priceMatch is required for futures modify order");
        }
        if (command.price() != null && hasText(command.priceMatch())) {
            throw new IllegalArgumentException("priceMatch cannot be used with price");
        }
        if (hasText(command.priceMatch())) {
            BinanceTradingCapability capability = BinanceTradingCapability.fromConfig(binance.trading());
            if (!capability.supportsPriceMatch()) {
                throw new IllegalArgumentException("priceMatch is not supported for this Binance market");
            }
            if (!capability.supportedPriceMatchOrderTypes().contains("LIMIT")) {
                throw new IllegalArgumentException("priceMatch is not supported for LIMIT modify order");
            }
        }
    }

    private void validateModifyMultipleOrders(List<BinanceModifyOrderCommand> commands) {
        Objects.requireNonNull(commands, "commands");
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("modifyMultipleOrders requires at least one order");
        }
        if (commands.size() > 5) {
            throw new IllegalArgumentException("modifyMultipleOrders can contain at most 5 orders");
        }
        for (BinanceModifyOrderCommand command : commands) {
            validateModifyOrder(command);
        }
    }

    private void validateModifyOrderHistoryQuery(BinanceModifyOrderHistoryQuery query) {
        Objects.requireNonNull(query, "query");
        requireSymbol(query.symbol());
        requirePositive("orderId", query.orderId());
        requirePositive("startTime", query.startTime());
        requirePositive("endTime", query.endTime());
        requirePositive("limit", query.limit());
        if (query.orderId() == null && !hasText(query.originalClientOrderId())) {
            throw new IllegalArgumentException("orderId or origClientOrderId is required");
        }
        if (query.limit() != null && query.limit() > 100) {
            throw new IllegalArgumentException("limit must be less than or equal to 100");
        }
    }

    private String modifyOrdersJson(List<BinanceModifyOrderCommand> commands) {
        List<Map<String, String>> orders = commands.stream()
                .map(this::modifyOrderParameterMap)
                .toList();
        return jsonMapper.writeValueAsString(orders);
    }

    private Map<String, String> modifyOrderParameterMap(BinanceModifyOrderCommand command) {
        Map<String, String> parameters = new LinkedHashMap<>();
        add(parameters, "symbol", command.symbol());
        add(parameters, "orderId", command.orderId());
        add(parameters, "origClientOrderId", command.originalClientOrderId());
        add(parameters, "side", command.side());
        add(parameters, "quantity", command.quantity());
        add(parameters, "price", command.price());
        add(parameters, "priceMatch", command.priceMatch());
        return parameters;
    }

    private void validateOrderHistoryQuery(BinanceOrderHistoryQuery query) {
        Objects.requireNonNull(query, "query");
        BinanceMarketType marketType = BinanceMarketType.fromConfigValue(binance.marketType());
        requirePositive("orderId", query.orderId());
        requirePositive("startTime", query.startTime());
        requirePositive("endTime", query.endTime());
        requirePositive("limit", query.limit());
        requireMarginFlagOnlyOnMargin(marketType, query.isolatedMargin());
        if (marketType == BinanceMarketType.FUTURES_COIN_M) {
            requireExactlyOneSymbolOrPair(query.symbol(), query.pair());
            if (hasText(query.pair()) && query.orderId() != null) {
                throw new IllegalArgumentException("orderId requires symbol for COIN-M all-orders queries");
            }
            return;
        }
        requireSymbol(query.symbol());
        requireNoPair(query.pair());
    }

    private void validateTradeHistoryQuery(BinanceTradeHistoryQuery query) {
        Objects.requireNonNull(query, "query");
        BinanceMarketType marketType = BinanceMarketType.fromConfigValue(binance.marketType());
        requirePositive("orderId", query.orderId());
        requirePositive("startTime", query.startTime());
        requirePositive("endTime", query.endTime());
        requirePositive("fromId", query.fromId());
        requirePositive("limit", query.limit());
        requireMarginFlagOnlyOnMargin(marketType, query.isolatedMargin());
        if (marketType == BinanceMarketType.FUTURES_COIN_M) {
            requireExactlyOneSymbolOrPair(query.symbol(), query.pair());
            if (hasText(query.pair()) && query.orderId() != null) {
                throw new IllegalArgumentException("orderId requires symbol for COIN-M account-trade queries");
            }
            if (hasText(query.pair()) && query.fromId() != null) {
                throw new IllegalArgumentException("fromId cannot be sent with pair for COIN-M account-trade queries");
            }
        } else {
            requireSymbol(query.symbol());
            requireNoPair(query.pair());
        }
        if (query.fromId() != null && (query.startTime() != null || query.endTime() != null)) {
            throw new IllegalArgumentException("fromId cannot be sent with startTime or endTime");
        }
    }

    private void validatePreventedMatchesQuery(BinancePreventedMatchesQuery query) {
        Objects.requireNonNull(query, "query");
        requireSymbol(query.symbol());
        requirePositive("preventedMatchId", query.preventedMatchId());
        requirePositive("orderId", query.orderId());
        requirePositive("fromPreventedMatchId", query.fromPreventedMatchId());
        requirePositive("limit", query.limit());
        if (query.limit() != null && query.limit() > 1000) {
            throw new IllegalArgumentException("limit must be less than or equal to 1000");
        }

        boolean hasPreventedMatchId = query.preventedMatchId() != null;
        boolean hasOrderId = query.orderId() != null;
        boolean hasFromPreventedMatchId = query.fromPreventedMatchId() != null;
        boolean hasLimit = query.limit() != null;
        if (hasPreventedMatchId) {
            if (hasOrderId || hasFromPreventedMatchId || hasLimit) {
                throw new IllegalArgumentException("preventedMatchId cannot be combined with orderId, fromPreventedMatchId, or limit");
            }
            return;
        }
        if (!hasOrderId) {
            throw new IllegalArgumentException("preventedMatchId or orderId is required");
        }
        if (hasLimit && !hasFromPreventedMatchId) {
            throw new IllegalArgumentException("limit requires fromPreventedMatchId for prevented-match queries");
        }
    }

    private void validateAmendKeepPriority(BinanceAmendKeepPriorityCommand command) {
        Objects.requireNonNull(command, "command");
        requireSymbol(command.symbol());
        requirePositive("orderId", command.orderId());
        requirePositive("newQty", command.newQuantity());
        if (command.orderId() == null && !hasText(command.originalClientOrderId())) {
            throw new IllegalArgumentException("orderId or origClientOrderId is required");
        }
        if (command.newQuantity() == null) {
            throw new IllegalArgumentException("newQty is required");
        }
    }

    private void validateCancelMultipleOrdersQuery(BinanceCancelMultipleOrdersQuery query) {
        Objects.requireNonNull(query, "query");
        requireSymbol(query.symbol());
        boolean hasOrderIds = !query.orderIds().isEmpty();
        boolean hasClientOrderIds = !query.originalClientOrderIds().isEmpty();
        if (hasOrderIds == hasClientOrderIds) {
            throw new IllegalArgumentException("exactly one of orderIds or originalClientOrderIds is required");
        }
        if (query.orderIds().size() > 10 || query.originalClientOrderIds().size() > 10) {
            throw new IllegalArgumentException("cancel-multiple order lists can contain at most 10 values");
        }
        for (Long orderId : query.orderIds()) {
            if (orderId == null || orderId <= 0) {
                throw new IllegalArgumentException("orderIds must be positive");
            }
        }
        for (String originalClientOrderId : query.originalClientOrderIds()) {
            if (!hasText(originalClientOrderId)) {
                throw new IllegalArgumentException("originalClientOrderIds must not contain blank values");
            }
        }
    }

    private void requirePositive(String name, Long value) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(name + " must be positive when configured");
        }
    }

    private void requirePositive(String name, Integer value) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(name + " must be positive when configured");
        }
    }

    private void requirePositive(String name, BigDecimal value) {
        if (value != null && value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive when configured");
        }
    }

    private void requireMarginFlagOnlyOnMargin(BinanceMarketType marketType, Boolean isolatedMargin) {
        if (isolatedMargin != null && marketType != BinanceMarketType.MARGIN_CROSS && marketType != BinanceMarketType.MARGIN_ISOLATED) {
            throw new IllegalArgumentException("isIsolated is only supported for margin history queries");
        }
    }

    private void requireHistoryPath(String name, String path) {
        requireConfiguredPath(name, path);
    }

    private void requireConfiguredPath(String name, String path) {
        if (!hasText(path)) {
            throw new IllegalArgumentException("Binance trading " + name + " is not configured for this market");
        }
    }

    private void requireSymbol(String symbol) {
        if (!hasText(symbol)) {
            throw new IllegalArgumentException("symbol is required");
        }
    }

    private void requireNoPair(String pair) {
        if (hasText(pair)) {
            throw new IllegalArgumentException("pair is only supported for COIN-M futures history queries");
        }
    }

    private void requireExactlyOneSymbolOrPair(String symbol, String pair) {
        if (hasText(symbol) == hasText(pair)) {
            throw new IllegalArgumentException("exactly one of symbol or pair is required");
        }
    }

    private void add(List<BinanceRequestParameter> parameters, String name, String value) {
        if (value != null && !value.isBlank()) {
            parameters.add(BinanceRequestParameter.of(name, value));
        }
    }

    private void add(List<BinanceRequestParameter> parameters, String name, BigDecimal value) {
        if (value != null) {
            parameters.add(BinanceRequestParameter.of(name, value.stripTrailingZeros().toPlainString()));
        }
    }

    private void add(List<BinanceRequestParameter> parameters, String name, Long value) {
        if (value != null) {
            parameters.add(BinanceRequestParameter.of(name, value.toString()));
        }
    }

    private void add(List<BinanceRequestParameter> parameters, String name, Integer value) {
        if (value != null) {
            parameters.add(BinanceRequestParameter.of(name, value.toString()));
        }
    }

    private void add(List<BinanceRequestParameter> parameters, String name, Boolean value) {
        if (Boolean.TRUE.equals(value)) {
            parameters.add(BinanceRequestParameter.of(name, value.toString()));
        }
    }

    private void add(Map<String, String> parameters, String name, String value) {
        if (value != null && !value.isBlank()) {
            parameters.put(name, value);
        }
    }

    private void add(Map<String, String> parameters, String name, BigDecimal value) {
        if (value != null) {
            parameters.put(name, value.stripTrailingZeros().toPlainString());
        }
    }

    private void add(Map<String, String> parameters, String name, Long value) {
        if (value != null) {
            parameters.put(name, value.toString());
        }
    }

    private void addWhenPresent(List<BinanceRequestParameter> parameters, String name, Boolean value) {
        if (value != null) {
            parameters.add(BinanceRequestParameter.of(name, value.toString()));
        }
    }

    private void addMarginIsolated(List<BinanceRequestParameter> parameters, Boolean value) {
        if (value != null) {
            parameters.add(BinanceRequestParameter.of("isIsolated", value ? "TRUE" : "FALSE"));
        }
    }

    private String longList(List<Long> values) {
        return values.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String stringList(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
