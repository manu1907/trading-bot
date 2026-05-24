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
        addWhenPresent(parameters, "autoRepayAtCancel", command.autoRepayAtCancel());
        add(parameters, "isIsolated", command.isolatedMargin());
        add(parameters, "marketMakerProtection", command.marketMakerProtection());
        return parameters;
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
}
