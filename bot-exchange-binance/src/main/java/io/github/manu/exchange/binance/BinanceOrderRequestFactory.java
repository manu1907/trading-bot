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
import java.util.Set;
import java.util.stream.Collectors;

final class BinanceOrderRequestFactory {

    private static final Set<String> CANCEL_REPLACE_MODES = Set.of("STOP_ON_FAILURE", "ALLOW_FAILURE");
    private static final Set<String> CANCEL_RESTRICTIONS = Set.of("ONLY_NEW", "ONLY_PARTIALLY_FILLED");
    private static final Set<String> ORDER_RATE_LIMIT_EXCEEDED_MODES = Set.of("DO_NOTHING", "CANCEL_ONLY");
    private static final Set<String> SOR_ORDER_TYPES = Set.of("LIMIT", "MARKET");
    private static final Set<String> OCO_ABOVE_TYPES = Set.of(
            "STOP_LOSS_LIMIT",
            "STOP_LOSS",
            "LIMIT_MAKER",
            "TAKE_PROFIT",
            "TAKE_PROFIT_LIMIT"
    );
    private static final Set<String> OCO_BELOW_TYPES = Set.of(
            "STOP_LOSS",
            "STOP_LOSS_LIMIT",
            "TAKE_PROFIT",
            "TAKE_PROFIT_LIMIT"
    );
    private static final Set<String> OCO_PROFIT_OR_LIMIT_TYPES = Set.of("LIMIT_MAKER", "TAKE_PROFIT", "TAKE_PROFIT_LIMIT");
    private static final Set<String> OCO_STOP_TYPES = Set.of("STOP_LOSS", "STOP_LOSS_LIMIT");
    private static final Set<String> LIMIT_PRICE_TYPES = Set.of("LIMIT_MAKER", "STOP_LOSS_LIMIT", "TAKE_PROFIT_LIMIT");
    private static final Set<String> STOP_TRIGGER_TYPES = Set.of("STOP_LOSS", "STOP_LOSS_LIMIT", "TAKE_PROFIT", "TAKE_PROFIT_LIMIT");
    private static final Set<String> OTO_WORKING_TYPES = Set.of("LIMIT", "LIMIT_MAKER");

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

    BinanceSignedRequest cancelReplace(BinanceCancelReplaceCommand command, String privateCredential) {
        requireConfiguredPath("cancelReplacePath", binance.trading().cancelReplacePath());
        validateCancelReplace(command);
        List<BinanceRequestParameter> parameters = new ArrayList<>(orderParameters(command.replacementOrder()));
        add(parameters, "cancelReplaceMode", command.cancelReplaceMode());
        add(parameters, "cancelNewClientOrderId", command.cancelNewClientOrderId());
        add(parameters, "cancelOrigClientOrderId", command.cancelOriginalClientOrderId());
        add(parameters, "cancelOrderId", command.cancelOrderId());
        add(parameters, "cancelRestrictions", command.cancelRestrictions());
        add(parameters, "orderRateLimitExceededMode", command.orderRateLimitExceededMode());
        return restRequestFactory.signedUri(binance.trading().cancelReplacePath(), parameters, privateCredential);
    }

    BinanceSignedRequest sorOrder(BinanceOrderCommand command, String privateCredential) {
        requireConfiguredPath("sorOrderPath", binance.trading().sorOrderPath());
        validateSorOrder(command);
        return restRequestFactory.signedUri(binance.trading().sorOrderPath(), orderParameters(command), privateCredential);
    }

    BinanceSignedRequest sorTestOrder(BinanceOrderCommand command, boolean computeCommissionRates, String privateCredential) {
        requireConfiguredPath("sorTestOrderPath", binance.trading().sorTestOrderPath());
        validateSorOrder(command);
        List<BinanceRequestParameter> parameters = new ArrayList<>(orderParameters(command));
        if (computeCommissionRates) {
            parameters.add(BinanceRequestParameter.of("computeCommissionRates", "true"));
        }
        return restRequestFactory.signedUri(binance.trading().sorTestOrderPath(), parameters, privateCredential);
    }

    BinanceSignedRequest ocoOrderList(BinanceOcoOrderListCommand command, String privateCredential) {
        requireConfiguredPath("orderListOcoPath", binance.trading().orderListOcoPath());
        validateOcoOrderList(command);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", command.symbol());
        add(parameters, "listClientOrderId", command.listClientOrderId());
        add(parameters, "side", command.side());
        add(parameters, "quantity", command.quantity());
        add(parameters, "aboveType", command.aboveType());
        add(parameters, "aboveClientOrderId", command.aboveClientOrderId());
        add(parameters, "aboveIcebergQty", command.aboveIcebergQuantity());
        add(parameters, "abovePrice", command.abovePrice());
        add(parameters, "aboveStopPrice", command.aboveStopPrice());
        add(parameters, "aboveTrailingDelta", command.aboveTrailingDelta());
        add(parameters, "aboveTimeInForce", command.aboveTimeInForce());
        add(parameters, "aboveStrategyId", command.aboveStrategyId());
        add(parameters, "aboveStrategyType", command.aboveStrategyType());
        add(parameters, "abovePegPriceType", command.abovePegPriceType());
        add(parameters, "abovePegOffsetType", command.abovePegOffsetType());
        add(parameters, "abovePegOffsetValue", command.abovePegOffsetValue());
        add(parameters, "belowType", command.belowType());
        add(parameters, "belowClientOrderId", command.belowClientOrderId());
        add(parameters, "belowIcebergQty", command.belowIcebergQuantity());
        add(parameters, "belowPrice", command.belowPrice());
        add(parameters, "belowStopPrice", command.belowStopPrice());
        add(parameters, "belowTrailingDelta", command.belowTrailingDelta());
        add(parameters, "belowTimeInForce", command.belowTimeInForce());
        add(parameters, "belowStrategyId", command.belowStrategyId());
        add(parameters, "belowStrategyType", command.belowStrategyType());
        add(parameters, "belowPegPriceType", command.belowPegPriceType());
        add(parameters, "belowPegOffsetType", command.belowPegOffsetType());
        add(parameters, "belowPegOffsetValue", command.belowPegOffsetValue());
        add(parameters, "newOrderRespType", command.orderResponseType());
        add(parameters, "selfTradePreventionMode", command.selfTradePreventionMode());
        return restRequestFactory.signedUri(binance.trading().orderListOcoPath(), parameters, privateCredential);
    }

    BinanceSignedRequest otoOrderList(BinanceOtoOrderListCommand command, String privateCredential) {
        requireConfiguredPath("orderListOtoPath", binance.trading().orderListOtoPath());
        validateOtoOrderList(command);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", command.symbol());
        add(parameters, "listClientOrderId", command.listClientOrderId());
        add(parameters, "newOrderRespType", command.orderResponseType());
        add(parameters, "selfTradePreventionMode", command.selfTradePreventionMode());
        add(parameters, "workingType", command.workingType());
        add(parameters, "workingSide", command.workingSide());
        add(parameters, "workingClientOrderId", command.workingClientOrderId());
        add(parameters, "workingPrice", command.workingPrice());
        add(parameters, "workingQuantity", command.workingQuantity());
        add(parameters, "workingIcebergQty", command.workingIcebergQuantity());
        add(parameters, "workingTimeInForce", command.workingTimeInForce());
        add(parameters, "workingStrategyId", command.workingStrategyId());
        add(parameters, "workingStrategyType", command.workingStrategyType());
        add(parameters, "workingPegPriceType", command.workingPegPriceType());
        add(parameters, "workingPegOffsetType", command.workingPegOffsetType());
        add(parameters, "workingPegOffsetValue", command.workingPegOffsetValue());
        add(parameters, "pendingType", command.pendingType());
        add(parameters, "pendingSide", command.pendingSide());
        add(parameters, "pendingClientOrderId", command.pendingClientOrderId());
        add(parameters, "pendingPrice", command.pendingPrice());
        add(parameters, "pendingStopPrice", command.pendingStopPrice());
        add(parameters, "pendingTrailingDelta", command.pendingTrailingDelta());
        add(parameters, "pendingQuantity", command.pendingQuantity());
        add(parameters, "pendingIcebergQty", command.pendingIcebergQuantity());
        add(parameters, "pendingTimeInForce", command.pendingTimeInForce());
        add(parameters, "pendingStrategyId", command.pendingStrategyId());
        add(parameters, "pendingStrategyType", command.pendingStrategyType());
        add(parameters, "pendingPegPriceType", command.pendingPegPriceType());
        add(parameters, "pendingPegOffsetType", command.pendingPegOffsetType());
        add(parameters, "pendingPegOffsetValue", command.pendingPegOffsetValue());
        return restRequestFactory.signedUri(binance.trading().orderListOtoPath(), parameters, privateCredential);
    }

    BinanceSignedRequest otocoOrderList(BinanceOtocoOrderListCommand command, String privateCredential) {
        requireConfiguredPath("orderListOtocoPath", binance.trading().orderListOtocoPath());
        validateOtocoOrderList(command);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", command.symbol());
        add(parameters, "listClientOrderId", command.listClientOrderId());
        add(parameters, "newOrderRespType", command.orderResponseType());
        add(parameters, "selfTradePreventionMode", command.selfTradePreventionMode());
        addWorkingOrderListParameters(parameters, command);
        add(parameters, "pendingSide", command.pendingSide());
        add(parameters, "pendingQuantity", command.pendingQuantity());
        add(parameters, "pendingAboveType", command.pendingAboveType());
        add(parameters, "pendingAboveClientOrderId", command.pendingAboveClientOrderId());
        add(parameters, "pendingAbovePrice", command.pendingAbovePrice());
        add(parameters, "pendingAboveStopPrice", command.pendingAboveStopPrice());
        add(parameters, "pendingAboveTrailingDelta", command.pendingAboveTrailingDelta());
        add(parameters, "pendingAboveIcebergQty", command.pendingAboveIcebergQuantity());
        add(parameters, "pendingAboveTimeInForce", command.pendingAboveTimeInForce());
        add(parameters, "pendingAboveStrategyId", command.pendingAboveStrategyId());
        add(parameters, "pendingAboveStrategyType", command.pendingAboveStrategyType());
        add(parameters, "pendingAbovePegPriceType", command.pendingAbovePegPriceType());
        add(parameters, "pendingAbovePegOffsetType", command.pendingAbovePegOffsetType());
        add(parameters, "pendingAbovePegOffsetValue", command.pendingAbovePegOffsetValue());
        add(parameters, "pendingBelowType", command.pendingBelowType());
        add(parameters, "pendingBelowClientOrderId", command.pendingBelowClientOrderId());
        add(parameters, "pendingBelowPrice", command.pendingBelowPrice());
        add(parameters, "pendingBelowStopPrice", command.pendingBelowStopPrice());
        add(parameters, "pendingBelowTrailingDelta", command.pendingBelowTrailingDelta());
        add(parameters, "pendingBelowIcebergQty", command.pendingBelowIcebergQuantity());
        add(parameters, "pendingBelowTimeInForce", command.pendingBelowTimeInForce());
        add(parameters, "pendingBelowStrategyId", command.pendingBelowStrategyId());
        add(parameters, "pendingBelowStrategyType", command.pendingBelowStrategyType());
        add(parameters, "pendingBelowPegPriceType", command.pendingBelowPegPriceType());
        add(parameters, "pendingBelowPegOffsetType", command.pendingBelowPegOffsetType());
        add(parameters, "pendingBelowPegOffsetValue", command.pendingBelowPegOffsetValue());
        return restRequestFactory.signedUri(binance.trading().orderListOtocoPath(), parameters, privateCredential);
    }

    BinanceSignedRequest opoOrderList(BinanceOpoOrderListCommand command, String privateCredential) {
        requireConfiguredPath("orderListOpoPath", binance.trading().orderListOpoPath());
        validateOpoOrderList(command);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", command.symbol());
        add(parameters, "listClientOrderId", command.listClientOrderId());
        add(parameters, "newOrderRespType", command.orderResponseType());
        add(parameters, "selfTradePreventionMode", command.selfTradePreventionMode());
        addWorkingOrderListParameters(parameters, command);
        add(parameters, "pendingType", command.pendingType());
        add(parameters, "pendingSide", command.pendingSide());
        add(parameters, "pendingClientOrderId", command.pendingClientOrderId());
        add(parameters, "pendingPrice", command.pendingPrice());
        add(parameters, "pendingStopPrice", command.pendingStopPrice());
        add(parameters, "pendingTrailingDelta", command.pendingTrailingDelta());
        add(parameters, "pendingIcebergQty", command.pendingIcebergQuantity());
        add(parameters, "pendingTimeInForce", command.pendingTimeInForce());
        add(parameters, "pendingStrategyId", command.pendingStrategyId());
        add(parameters, "pendingStrategyType", command.pendingStrategyType());
        add(parameters, "pendingPegPriceType", command.pendingPegPriceType());
        add(parameters, "pendingPegOffsetType", command.pendingPegOffsetType());
        add(parameters, "pendingPegOffsetValue", command.pendingPegOffsetValue());
        return restRequestFactory.signedUri(binance.trading().orderListOpoPath(), parameters, privateCredential);
    }

    BinanceSignedRequest opocoOrderList(BinanceOpocoOrderListCommand command, String privateCredential) {
        requireConfiguredPath("orderListOpocoPath", binance.trading().orderListOpocoPath());
        validateOpocoOrderList(command);
        List<BinanceRequestParameter> parameters = new ArrayList<>();
        add(parameters, "symbol", command.symbol());
        add(parameters, "listClientOrderId", command.listClientOrderId());
        add(parameters, "newOrderRespType", command.orderResponseType());
        add(parameters, "selfTradePreventionMode", command.selfTradePreventionMode());
        addWorkingOrderListParameters(parameters, command);
        add(parameters, "pendingSide", command.pendingSide());
        add(parameters, "pendingAboveType", command.pendingAboveType());
        add(parameters, "pendingAboveClientOrderId", command.pendingAboveClientOrderId());
        add(parameters, "pendingAbovePrice", command.pendingAbovePrice());
        add(parameters, "pendingAboveStopPrice", command.pendingAboveStopPrice());
        add(parameters, "pendingAboveTrailingDelta", command.pendingAboveTrailingDelta());
        add(parameters, "pendingAboveIcebergQty", command.pendingAboveIcebergQuantity());
        add(parameters, "pendingAboveTimeInForce", command.pendingAboveTimeInForce());
        add(parameters, "pendingAboveStrategyId", command.pendingAboveStrategyId());
        add(parameters, "pendingAboveStrategyType", command.pendingAboveStrategyType());
        add(parameters, "pendingAbovePegPriceType", command.pendingAbovePegPriceType());
        add(parameters, "pendingAbovePegOffsetType", command.pendingAbovePegOffsetType());
        add(parameters, "pendingAbovePegOffsetValue", command.pendingAbovePegOffsetValue());
        add(parameters, "pendingBelowType", command.pendingBelowType());
        add(parameters, "pendingBelowClientOrderId", command.pendingBelowClientOrderId());
        add(parameters, "pendingBelowPrice", command.pendingBelowPrice());
        add(parameters, "pendingBelowStopPrice", command.pendingBelowStopPrice());
        add(parameters, "pendingBelowTrailingDelta", command.pendingBelowTrailingDelta());
        add(parameters, "pendingBelowIcebergQty", command.pendingBelowIcebergQuantity());
        add(parameters, "pendingBelowTimeInForce", command.pendingBelowTimeInForce());
        add(parameters, "pendingBelowStrategyId", command.pendingBelowStrategyId());
        add(parameters, "pendingBelowStrategyType", command.pendingBelowStrategyType());
        add(parameters, "pendingBelowPegPriceType", command.pendingBelowPegPriceType());
        add(parameters, "pendingBelowPegOffsetType", command.pendingBelowPegOffsetType());
        add(parameters, "pendingBelowPegOffsetValue", command.pendingBelowPegOffsetValue());
        return restRequestFactory.signedUri(binance.trading().orderListOpocoPath(), parameters, privateCredential);
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

    private void validateCancelReplace(BinanceCancelReplaceCommand command) {
        Objects.requireNonNull(command, "command");
        BinanceOrderCommandValidator.validate(command.replacementOrder(), binance.trading());
        requireOneOf("cancelReplaceMode", command.cancelReplaceMode(), CANCEL_REPLACE_MODES);
        requireOptionalOneOf("cancelRestrictions", command.cancelRestrictions(), CANCEL_RESTRICTIONS);
        requireOptionalOneOf("orderRateLimitExceededMode", command.orderRateLimitExceededMode(), ORDER_RATE_LIMIT_EXCEEDED_MODES);
        requirePositive("cancelOrderId", command.cancelOrderId());
        if (command.cancelOrderId() == null && !hasText(command.cancelOriginalClientOrderId())) {
            throw new IllegalArgumentException("cancelOrderId or cancelOrigClientOrderId is required");
        }
    }

    private void validateSorOrder(BinanceOrderCommand command) {
        BinanceOrderCommandValidator.validate(command, binance.trading());
        if (!SOR_ORDER_TYPES.contains(command.type())) {
            throw new IllegalArgumentException("SOR orders only support LIMIT and MARKET order types");
        }
        if (command.quantity() == null) {
            throw new IllegalArgumentException("quantity is required for SOR orders");
        }
        if (command.quoteOrderQty() != null) {
            throw new IllegalArgumentException("quoteOrderQty is not supported for SOR orders");
        }
        if (hasText(command.pegPriceType()) || hasText(command.pegOffsetType()) || command.pegOffsetValue() != null) {
            throw new IllegalArgumentException("pegged order parameters are not supported for SOR orders");
        }
    }

    private void validateOcoOrderList(BinanceOcoOrderListCommand command) {
        Objects.requireNonNull(command, "command");
        BinanceTradingCapability capability = BinanceTradingCapability.fromConfig(binance.trading());
        requireSymbol(command.symbol());
        requireOneOf("side", command.side(), capability.supportedSides());
        requirePositive("quantity", command.quantity());
        if (command.quantity() == null) {
            throw new IllegalArgumentException("quantity is required for OCO order lists");
        }
        requireOneOf("aboveType", command.aboveType(), OCO_ABOVE_TYPES);
        requireOneOf("belowType", command.belowType(), OCO_BELOW_TYPES);
        if (!isValidOcoPair(command.aboveType(), command.belowType())) {
            throw new IllegalArgumentException("OCO order lists require one limit/profit leg and one stop-loss leg");
        }
        validateOcoLeg("above", command.aboveType(), command.abovePrice(), command.aboveStopPrice(), command.aboveTrailingDelta(),
                command.aboveTimeInForce(), command.aboveIcebergQuantity());
        validateOcoLeg("below", command.belowType(), command.belowPrice(), command.belowStopPrice(), command.belowTrailingDelta(),
                command.belowTimeInForce(), command.belowIcebergQuantity());
        validateStrategyType("aboveStrategyType", command.aboveStrategyType());
        validateStrategyType("belowStrategyType", command.belowStrategyType());
        requireOptionalOneOf("newOrderRespType", command.orderResponseType(), capability.supportedOrderResponseTypes());
        requireOptionalOneOf("selfTradePreventionMode", command.selfTradePreventionMode(), capability.supportedSelfTradePreventionModes());
        validatePeggedOcoLeg("above", command.abovePegPriceType(), command.abovePegOffsetType(), command.abovePegOffsetValue(), capability);
        validatePeggedOcoLeg("below", command.belowPegPriceType(), command.belowPegOffsetType(), command.belowPegOffsetValue(), capability);
    }

    private void validateOtoOrderList(BinanceOtoOrderListCommand command) {
        Objects.requireNonNull(command, "command");
        BinanceTradingCapability capability = BinanceTradingCapability.fromConfig(binance.trading());
        requireSymbol(command.symbol());
        validateWorkingOrderListLeg(
                command.workingType(),
                command.workingSide(),
                command.workingPrice(),
                command.workingQuantity(),
                command.workingIcebergQuantity(),
                command.workingTimeInForce(),
                command.workingStrategyType(),
                command.workingPegPriceType(),
                command.workingPegOffsetType(),
                command.workingPegOffsetValue(),
                "OTO",
                capability
        );
        requireOneOf("pendingType", command.pendingType(), capability.supportedOrderTypes());
        if ("MARKET".equals(command.pendingType()) && command.pendingPrice() != null) {
            throw new IllegalArgumentException("pendingPrice is not supported for MARKET OTO pending orders");
        }
        requireOneOf("pendingSide", command.pendingSide(), capability.supportedSides());
        requirePositive("pendingPrice", command.pendingPrice());
        requirePositive("pendingStopPrice", command.pendingStopPrice());
        requirePositive("pendingTrailingDelta", command.pendingTrailingDelta());
        requirePositive("pendingQuantity", command.pendingQuantity());
        requirePositive("pendingIcebergQty", command.pendingIcebergQuantity());
        if (command.pendingQuantity() == null) {
            throw new IllegalArgumentException("pendingQuantity is required for OTO order lists");
        }
        validatePendingOrderLeg(
                "pending",
                command.pendingType(),
                command.pendingPrice(),
                command.pendingStopPrice(),
                command.pendingTrailingDelta(),
                command.pendingTimeInForce(),
                command.pendingIcebergQuantity()
        );
        validateStrategyType("pendingStrategyType", command.pendingStrategyType());
        validatePeggedOcoLeg("pending", command.pendingPegPriceType(), command.pendingPegOffsetType(), command.pendingPegOffsetValue(), capability);
        requireOptionalOneOf("newOrderRespType", command.orderResponseType(), capability.supportedOrderResponseTypes());
        requireOptionalOneOf("selfTradePreventionMode", command.selfTradePreventionMode(), capability.supportedSelfTradePreventionModes());
    }

    private void validateOtocoOrderList(BinanceOtocoOrderListCommand command) {
        Objects.requireNonNull(command, "command");
        BinanceTradingCapability capability = BinanceTradingCapability.fromConfig(binance.trading());
        requireSymbol(command.symbol());
        validateWorkingOrderListLeg(
                command.workingType(),
                command.workingSide(),
                command.workingPrice(),
                command.workingQuantity(),
                command.workingIcebergQuantity(),
                command.workingTimeInForce(),
                command.workingStrategyType(),
                command.workingPegPriceType(),
                command.workingPegOffsetType(),
                command.workingPegOffsetValue(),
                "OTOCO",
                capability
        );
        requireOneOf("pendingSide", command.pendingSide(), capability.supportedSides());
        requirePositive("pendingQuantity", command.pendingQuantity());
        if (command.pendingQuantity() == null) {
            throw new IllegalArgumentException("pendingQuantity is required for OTOCO order lists");
        }
        requireOneOf("pendingAboveType", command.pendingAboveType(), OCO_ABOVE_TYPES);
        requireOneOf("pendingBelowType", command.pendingBelowType(), OCO_BELOW_TYPES);
        if (!isValidOcoPair(command.pendingAboveType(), command.pendingBelowType())) {
            throw new IllegalArgumentException("OTOCO order lists require one limit/profit pending leg and one stop-loss pending leg");
        }
        validateOcoLeg(
                "pendingAbove",
                command.pendingAboveType(),
                command.pendingAbovePrice(),
                command.pendingAboveStopPrice(),
                command.pendingAboveTrailingDelta(),
                command.pendingAboveTimeInForce(),
                command.pendingAboveIcebergQuantity()
        );
        validateOcoLeg(
                "pendingBelow",
                command.pendingBelowType(),
                command.pendingBelowPrice(),
                command.pendingBelowStopPrice(),
                command.pendingBelowTrailingDelta(),
                command.pendingBelowTimeInForce(),
                command.pendingBelowIcebergQuantity()
        );
        validateStrategyType("pendingAboveStrategyType", command.pendingAboveStrategyType());
        validateStrategyType("pendingBelowStrategyType", command.pendingBelowStrategyType());
        validatePeggedOcoLeg(
                "pendingAbove",
                command.pendingAbovePegPriceType(),
                command.pendingAbovePegOffsetType(),
                command.pendingAbovePegOffsetValue(),
                capability
        );
        validatePeggedOcoLeg(
                "pendingBelow",
                command.pendingBelowPegPriceType(),
                command.pendingBelowPegOffsetType(),
                command.pendingBelowPegOffsetValue(),
                capability
        );
        requireOptionalOneOf("newOrderRespType", command.orderResponseType(), capability.supportedOrderResponseTypes());
        requireOptionalOneOf("selfTradePreventionMode", command.selfTradePreventionMode(), capability.supportedSelfTradePreventionModes());
    }

    private void validateOpoOrderList(BinanceOpoOrderListCommand command) {
        Objects.requireNonNull(command, "command");
        BinanceTradingCapability capability = BinanceTradingCapability.fromConfig(binance.trading());
        requireSymbol(command.symbol());
        validateWorkingOrderListLeg(
                command.workingType(),
                command.workingSide(),
                command.workingPrice(),
                command.workingQuantity(),
                command.workingIcebergQuantity(),
                command.workingTimeInForce(),
                command.workingStrategyType(),
                command.workingPegPriceType(),
                command.workingPegOffsetType(),
                command.workingPegOffsetValue(),
                "OPO",
                capability
        );
        requireOneOf("pendingType", command.pendingType(), capability.supportedOrderTypes());
        if ("MARKET".equals(command.pendingType()) && command.pendingPrice() != null) {
            throw new IllegalArgumentException("pendingPrice is not supported for MARKET OPO pending orders");
        }
        requireOneOf("pendingSide", command.pendingSide(), capability.supportedSides());
        requirePositive("pendingPrice", command.pendingPrice());
        requirePositive("pendingStopPrice", command.pendingStopPrice());
        requirePositive("pendingTrailingDelta", command.pendingTrailingDelta());
        requirePositive("pendingIcebergQty", command.pendingIcebergQuantity());
        validatePendingOrderLeg(
                "pending",
                command.pendingType(),
                command.pendingPrice(),
                command.pendingStopPrice(),
                command.pendingTrailingDelta(),
                command.pendingTimeInForce(),
                command.pendingIcebergQuantity()
        );
        validateStrategyType("pendingStrategyType", command.pendingStrategyType());
        validatePeggedOcoLeg("pending", command.pendingPegPriceType(), command.pendingPegOffsetType(), command.pendingPegOffsetValue(), capability);
        requireOptionalOneOf("newOrderRespType", command.orderResponseType(), capability.supportedOrderResponseTypes());
        requireOptionalOneOf("selfTradePreventionMode", command.selfTradePreventionMode(), capability.supportedSelfTradePreventionModes());
    }

    private void validateOpocoOrderList(BinanceOpocoOrderListCommand command) {
        Objects.requireNonNull(command, "command");
        BinanceTradingCapability capability = BinanceTradingCapability.fromConfig(binance.trading());
        requireSymbol(command.symbol());
        validateWorkingOrderListLeg(
                command.workingType(),
                command.workingSide(),
                command.workingPrice(),
                command.workingQuantity(),
                command.workingIcebergQuantity(),
                command.workingTimeInForce(),
                command.workingStrategyType(),
                command.workingPegPriceType(),
                command.workingPegOffsetType(),
                command.workingPegOffsetValue(),
                "OPOCO",
                capability
        );
        requireOneOf("pendingSide", command.pendingSide(), capability.supportedSides());
        requireOneOf("pendingAboveType", command.pendingAboveType(), OCO_ABOVE_TYPES);
        requireOneOf("pendingBelowType", command.pendingBelowType(), OCO_BELOW_TYPES);
        if (!isValidOcoPair(command.pendingAboveType(), command.pendingBelowType())) {
            throw new IllegalArgumentException("OPOCO order lists require one limit/profit pending leg and one stop-loss pending leg");
        }
        validateOcoLeg(
                "pendingAbove",
                command.pendingAboveType(),
                command.pendingAbovePrice(),
                command.pendingAboveStopPrice(),
                command.pendingAboveTrailingDelta(),
                command.pendingAboveTimeInForce(),
                command.pendingAboveIcebergQuantity()
        );
        validateOcoLeg(
                "pendingBelow",
                command.pendingBelowType(),
                command.pendingBelowPrice(),
                command.pendingBelowStopPrice(),
                command.pendingBelowTrailingDelta(),
                command.pendingBelowTimeInForce(),
                command.pendingBelowIcebergQuantity()
        );
        validateStrategyType("pendingAboveStrategyType", command.pendingAboveStrategyType());
        validateStrategyType("pendingBelowStrategyType", command.pendingBelowStrategyType());
        validatePeggedOcoLeg(
                "pendingAbove",
                command.pendingAbovePegPriceType(),
                command.pendingAbovePegOffsetType(),
                command.pendingAbovePegOffsetValue(),
                capability
        );
        validatePeggedOcoLeg(
                "pendingBelow",
                command.pendingBelowPegPriceType(),
                command.pendingBelowPegOffsetType(),
                command.pendingBelowPegOffsetValue(),
                capability
        );
        requireOptionalOneOf("newOrderRespType", command.orderResponseType(), capability.supportedOrderResponseTypes());
        requireOptionalOneOf("selfTradePreventionMode", command.selfTradePreventionMode(), capability.supportedSelfTradePreventionModes());
    }

    private void validateWorkingOrderListLeg(String type,
                                             String side,
                                             BigDecimal price,
                                             BigDecimal quantity,
                                             BigDecimal icebergQuantity,
                                             String timeInForce,
                                             Integer strategyType,
                                             String pegPriceType,
                                             String pegOffsetType,
                                             Integer pegOffsetValue,
                                             String listType,
                                             BinanceTradingCapability capability) {
        requireOneOf("workingType", type, OTO_WORKING_TYPES);
        requireOneOf("workingSide", side, capability.supportedSides());
        requirePositive("workingPrice", price);
        requirePositive("workingQuantity", quantity);
        requirePositive("workingIcebergQty", icebergQuantity);
        if (price == null) {
            throw new IllegalArgumentException("workingPrice is required for " + listType + " order lists");
        }
        if (quantity == null) {
            throw new IllegalArgumentException("workingQuantity is required for " + listType + " order lists");
        }
        if ("LIMIT".equals(type) && !hasText(timeInForce)) {
            throw new IllegalArgumentException("workingTimeInForce is required for LIMIT " + listType + " working orders");
        }
        requireOptionalOneOf("workingTimeInForce", timeInForce, capability.supportedTimeInForce());
        validateIcebergTimeInForce("working", type, icebergQuantity, timeInForce);
        validateStrategyType("workingStrategyType", strategyType);
        validatePeggedOcoLeg("working", pegPriceType, pegOffsetType, pegOffsetValue, capability);
    }

    private void addWorkingOrderListParameters(List<BinanceRequestParameter> parameters, BinanceOtocoOrderListCommand command) {
        add(parameters, "workingType", command.workingType());
        add(parameters, "workingSide", command.workingSide());
        add(parameters, "workingClientOrderId", command.workingClientOrderId());
        add(parameters, "workingPrice", command.workingPrice());
        add(parameters, "workingQuantity", command.workingQuantity());
        add(parameters, "workingIcebergQty", command.workingIcebergQuantity());
        add(parameters, "workingTimeInForce", command.workingTimeInForce());
        add(parameters, "workingStrategyId", command.workingStrategyId());
        add(parameters, "workingStrategyType", command.workingStrategyType());
        add(parameters, "workingPegPriceType", command.workingPegPriceType());
        add(parameters, "workingPegOffsetType", command.workingPegOffsetType());
        add(parameters, "workingPegOffsetValue", command.workingPegOffsetValue());
    }

    private void addWorkingOrderListParameters(List<BinanceRequestParameter> parameters, BinanceOpoOrderListCommand command) {
        add(parameters, "workingType", command.workingType());
        add(parameters, "workingSide", command.workingSide());
        add(parameters, "workingClientOrderId", command.workingClientOrderId());
        add(parameters, "workingPrice", command.workingPrice());
        add(parameters, "workingQuantity", command.workingQuantity());
        add(parameters, "workingIcebergQty", command.workingIcebergQuantity());
        add(parameters, "workingTimeInForce", command.workingTimeInForce());
        add(parameters, "workingStrategyId", command.workingStrategyId());
        add(parameters, "workingStrategyType", command.workingStrategyType());
        add(parameters, "workingPegPriceType", command.workingPegPriceType());
        add(parameters, "workingPegOffsetType", command.workingPegOffsetType());
        add(parameters, "workingPegOffsetValue", command.workingPegOffsetValue());
    }

    private void addWorkingOrderListParameters(List<BinanceRequestParameter> parameters, BinanceOpocoOrderListCommand command) {
        add(parameters, "workingType", command.workingType());
        add(parameters, "workingSide", command.workingSide());
        add(parameters, "workingClientOrderId", command.workingClientOrderId());
        add(parameters, "workingPrice", command.workingPrice());
        add(parameters, "workingQuantity", command.workingQuantity());
        add(parameters, "workingIcebergQty", command.workingIcebergQuantity());
        add(parameters, "workingTimeInForce", command.workingTimeInForce());
        add(parameters, "workingStrategyId", command.workingStrategyId());
        add(parameters, "workingStrategyType", command.workingStrategyType());
        add(parameters, "workingPegPriceType", command.workingPegPriceType());
        add(parameters, "workingPegOffsetType", command.workingPegOffsetType());
        add(parameters, "workingPegOffsetValue", command.workingPegOffsetValue());
    }

    private boolean isValidOcoPair(String aboveType, String belowType) {
        return (OCO_PROFIT_OR_LIMIT_TYPES.contains(aboveType) && OCO_STOP_TYPES.contains(belowType))
                || (OCO_STOP_TYPES.contains(aboveType) && OCO_PROFIT_OR_LIMIT_TYPES.contains(belowType));
    }

    private void validateOcoLeg(String prefix,
                                String type,
                                BigDecimal price,
                                BigDecimal stopPrice,
                                Long trailingDelta,
                                String timeInForce,
                                BigDecimal icebergQuantity) {
        requirePositive(prefix + "Price", price);
        requirePositive(prefix + "StopPrice", stopPrice);
        requirePositive(prefix + "TrailingDelta", trailingDelta);
        requirePositive(prefix + "IcebergQty", icebergQuantity);
        requireOptionalOneOf(prefix + "TimeInForce", timeInForce, BinanceTradingCapability.fromConfig(binance.trading()).supportedTimeInForce());
        if (LIMIT_PRICE_TYPES.contains(type) && price == null) {
            throw new IllegalArgumentException(prefix + "Price is required for " + type + " OCO legs");
        }
        if (STOP_TRIGGER_TYPES.contains(type) && stopPrice == null && trailingDelta == null) {
            throw new IllegalArgumentException(prefix + "StopPrice or " + prefix + "TrailingDelta is required for " + type + " OCO legs");
        }
        if (type.endsWith("_LIMIT") && !hasText(timeInForce)) {
            throw new IllegalArgumentException(prefix + "TimeInForce is required for " + type + " OCO legs");
        }
        if (icebergQuantity != null && hasText(timeInForce) && !"GTC".equals(timeInForce)) {
            throw new IllegalArgumentException(prefix + "IcebergQty requires " + prefix + "TimeInForce GTC");
        }
    }

    private void validatePendingOrderLeg(String prefix,
                                         String type,
                                         BigDecimal price,
                                         BigDecimal stopPrice,
                                         Long trailingDelta,
                                         String timeInForce,
                                         BigDecimal icebergQuantity) {
        requireOptionalOneOf(prefix + "TimeInForce", timeInForce, BinanceTradingCapability.fromConfig(binance.trading()).supportedTimeInForce());
        if (("LIMIT".equals(type) || LIMIT_PRICE_TYPES.contains(type)) && price == null) {
            throw new IllegalArgumentException(prefix + "Price is required for " + type + " OTO pending orders");
        }
        if (("LIMIT".equals(type) || type.endsWith("_LIMIT")) && !hasText(timeInForce)) {
            throw new IllegalArgumentException(prefix + "TimeInForce is required for " + type + " OTO pending orders");
        }
        if (STOP_TRIGGER_TYPES.contains(type) && stopPrice == null && trailingDelta == null) {
            throw new IllegalArgumentException(prefix + "StopPrice or " + prefix + "TrailingDelta is required for "
                    + type + " OTO pending orders");
        }
        validateIcebergTimeInForce(prefix, type, icebergQuantity, timeInForce);
    }

    private void validateIcebergTimeInForce(String prefix, String type, BigDecimal icebergQuantity, String timeInForce) {
        if (icebergQuantity != null && !"LIMIT_MAKER".equals(type) && !"GTC".equals(timeInForce)) {
            throw new IllegalArgumentException(prefix + "IcebergQty requires " + prefix + "TimeInForce GTC unless type is LIMIT_MAKER");
        }
    }

    private void validateStrategyType(String name, Integer strategyType) {
        if (strategyType != null && strategyType < 1_000_000) {
            throw new IllegalArgumentException(name + " must be greater than or equal to 1000000 when configured");
        }
    }

    private void validatePeggedOcoLeg(String prefix,
                                      String pegPriceType,
                                      String pegOffsetType,
                                      Integer pegOffsetValue,
                                      BinanceTradingCapability capability) {
        if (!hasText(pegPriceType) && !hasText(pegOffsetType) && pegOffsetValue == null) {
            return;
        }
        if (!capability.supportsPeggedOrders()) {
            throw new IllegalArgumentException(prefix + " pegged order parameters are not supported for this Binance market");
        }
        requireOptionalOneOf(prefix + "PegPriceType", pegPriceType, capability.supportedPegPriceTypes());
        requireOptionalOneOf(prefix + "PegOffsetType", pegOffsetType, capability.supportedPegOffsetTypes());
        requirePositive(prefix + "PegOffsetValue", pegOffsetValue);
        if (hasText(pegOffsetType) != (pegOffsetValue != null)) {
            throw new IllegalArgumentException(prefix + "PegOffsetType and " + prefix + "PegOffsetValue must be sent together");
        }
        if (pegOffsetValue != null && capability.maxPegOffsetValue() != null && pegOffsetValue > capability.maxPegOffsetValue()) {
            throw new IllegalArgumentException(prefix + "PegOffsetValue must be less than or equal to "
                    + capability.maxPegOffsetValue());
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

    private void requireOneOf(String name, String value, Set<String> allowed) {
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(name + " must be one of " + allowed);
        }
    }

    private void requireOptionalOneOf(String name, String value, Set<String> allowed) {
        if (hasText(value) && !allowed.contains(value)) {
            throw new IllegalArgumentException(name + " must be one of " + allowed);
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
