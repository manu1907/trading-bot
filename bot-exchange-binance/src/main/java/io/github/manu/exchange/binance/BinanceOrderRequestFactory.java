package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BinanceOrderRequestFactory {

    private final BinanceProperties binance;
    private final BinanceRestRequestFactory restRequestFactory;

    BinanceOrderRequestFactory(BinanceProperties binance, Clock clock, long serverTimeOffsetMillis) {
        this.binance = Objects.requireNonNull(binance, "binance");
        this.restRequestFactory = new BinanceRestRequestFactory(binance.rest(), clock, serverTimeOffsetMillis);
    }

    BinanceSignedRequest newOrder(BinanceOrderCommand command, String privateCredential) {
        BinanceOrderCommandValidator.validate(command, binance.trading());
        return restRequestFactory.signedUri(binance.trading().newOrderPath(), orderParameters(command), privateCredential);
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

    private void requireMarginFlagOnlyOnMargin(BinanceMarketType marketType, Boolean isolatedMargin) {
        if (isolatedMargin != null && marketType != BinanceMarketType.MARGIN_CROSS && marketType != BinanceMarketType.MARGIN_ISOLATED) {
            throw new IllegalArgumentException("isIsolated is only supported for margin history queries");
        }
    }

    private void requireHistoryPath(String name, String path) {
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
