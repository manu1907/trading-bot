package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class BinanceOrderCommandValidator {

    private static final Set<String> SPOT_MARKET_STOP_TYPES = Set.of("STOP_LOSS", "TAKE_PROFIT");
    private static final Set<String> SPOT_LIMIT_STOP_TYPES = Set.of("STOP_LOSS_LIMIT", "TAKE_PROFIT_LIMIT");
    private static final Set<String> FUTURES_LIMIT_STOP_TYPES = Set.of("STOP", "TAKE_PROFIT");
    private static final Set<String> FUTURES_MARKET_STOP_TYPES = Set.of("STOP_MARKET", "TAKE_PROFIT_MARKET");

    private BinanceOrderCommandValidator() {
    }

    static void validate(BinanceOrderCommand command, BinanceMarketType marketType) {
        validate(command, BinanceTradingCapability.forMarketType(marketType));
    }

    static void validate(BinanceOrderCommand command, BinanceProperties.Trading trading) {
        validate(command, BinanceTradingCapability.fromConfig(trading));
    }

    private static void validate(BinanceOrderCommand command, BinanceTradingCapability capability) {
        List<String> errors = new ArrayList<>();
        if (command == null) {
            throw new IllegalArgumentException("Binance order command is required");
        }

        requireText("symbol", command.symbol(), errors);
        requireOneOf("side", command.side(), capability.supportedSides(), errors);
        requireOneOf("type", command.type(), capability.supportedOrderTypes(), errors);
        requireOptionalOneOf("timeInForce", command.timeInForce(), capability.supportedTimeInForce(), errors);
        requireOptionalOneOf("newOrderRespType", command.orderResponseType(), capability.supportedOrderResponseTypes(), errors);
        requireOptionalOneOf(
                "selfTradePreventionMode",
                command.selfTradePreventionMode(),
                capability.supportedSelfTradePreventionModes(),
                errors
        );
        requireOptionalOneOf("positionSide", command.positionSide(), capability.supportedPositionSides(), errors);
        requireOptionalOneOf("sideEffectType", command.sideEffectType(), capability.supportedMarginSideEffectTypes(), errors);
        requireOptionalOneOf("workingType", command.workingType(), capability.supportedWorkingTypes(), errors);
        requireOptionalOneOf("pegPriceType", command.pegPriceType(), capability.supportedPegPriceTypes(), errors);
        requireOptionalOneOf("pegOffsetType", command.pegOffsetType(), capability.supportedPegOffsetTypes(), errors);
        validateFeatureFlags(command, capability, errors);
        validatePositiveNumbers(command, capability, errors);
        validateParameterCombinations(command, capability, errors);
        validateOrderTypeParameters(command, capability, errors);

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private static void validateFeatureFlags(BinanceOrderCommand command,
                                             BinanceTradingCapability capability,
                                             List<String> errors) {
        if (hasValue(command.quoteOrderQty()) && !capability.supportsQuoteOrderQty()) {
            errors.add("quoteOrderQty is not supported for this Binance market");
        }
        if (Boolean.TRUE.equals(command.reduceOnly()) && !capability.supportsReduceOnly()) {
            errors.add("reduceOnly is not supported for this Binance market");
        }
        if (Boolean.TRUE.equals(command.closePosition()) && !capability.supportsClosePosition()) {
            errors.add("closePosition is not supported for this Binance market");
        }
        if (hasText(command.priceMatch()) && !capability.supportsPriceMatch()) {
            errors.add("priceMatch is not supported for this Binance market");
        }
        if (hasText(command.workingType()) && !capability.supportsWorkingType()) {
            errors.add("workingType is not supported for this Binance market");
        }
        if (command.priceProtect() != null && !capability.supportsPriceProtect()) {
            errors.add("priceProtect is not supported for this Binance market");
        }
        if (hasPeggedOrderParameter(command) && !capability.supportsPeggedOrders()) {
            errors.add("pegged orders are not supported for this Binance market");
        }
        if (hasText(command.sideEffectType()) && !capability.supportsMarginSideEffectControls()) {
            errors.add("sideEffectType is not supported for this Binance market");
        }
        if (command.autoRepayAtCancel() != null && !capability.supportsMarginSideEffectControls()) {
            errors.add("autoRepayAtCancel is not supported for this Binance market");
        }
        if (hasValue(command.icebergQty()) && !capability.supportsIcebergQty()) {
            errors.add("icebergQty is not supported for this Binance market");
        }
        if (hasValue(command.trailingDelta()) && !capability.supportsTrailingDelta()) {
            errors.add("trailingDelta is not supported for this Binance market");
        }
        if (Boolean.TRUE.equals(command.isolatedMargin()) && !capability.supportsIsolatedMarginFlag()) {
            errors.add("isIsolated is not supported for this Binance market");
        }
        if (Boolean.TRUE.equals(command.marketMakerProtection()) && !capability.supportsMarketMakerProtection()) {
            errors.add("marketMakerProtection is not supported for this Binance market");
        }
        if (command.postOnly() != null && !capability.supportsPostOnly()) {
            errors.add("postOnly is not supported for this Binance market");
        }
    }

    private static void validatePositiveNumbers(BinanceOrderCommand command,
                                                BinanceTradingCapability capability,
                                                List<String> errors) {
        requirePositiveWhenPresent("quantity", command.quantity(), errors);
        requirePositiveWhenPresent("quoteOrderQty", command.quoteOrderQty(), errors);
        requirePositiveWhenPresent("price", command.price(), errors);
        requirePositiveWhenPresent("stopPrice", command.stopPrice(), errors);
        requirePositiveWhenPresent("trailingDelta", command.trailingDelta(), errors);
        requirePositiveWhenPresent("callbackRate", command.callbackRate(), errors);
        requirePositiveWhenPresent("activationPrice", command.activationPrice(), errors);
        requirePositiveWhenPresent("icebergQty", command.icebergQty(), errors);
        requirePositiveWhenPresent("goodTillDate", command.goodTillDate(), errors);
        requirePositiveWhenPresent("pegOffsetValue", command.pegOffsetValue(), errors);
        if (command.pegOffsetValue() != null
                && capability.maxPegOffsetValue() != null
                && command.pegOffsetValue() > capability.maxPegOffsetValue()) {
            errors.add("pegOffsetValue must be less than or equal to " + capability.maxPegOffsetValue());
        }
    }

    private static void validateParameterCombinations(BinanceOrderCommand command,
                                                      BinanceTradingCapability capability,
                                                      List<String> errors) {
        if (hasText(command.priceMatch())) {
            if (hasValue(command.price())) {
                errors.add("priceMatch cannot be used with price");
            }
            if (hasText(command.type()) && !capability.supportedPriceMatchOrderTypes().contains(command.type())) {
                errors.add("priceMatch is not supported for order type " + command.type());
            }
        }
        if (hasText(command.workingType())
                && hasText(command.type())
                && !capability.supportedWorkingTypeOrderTypes().contains(command.type())) {
            errors.add("workingType is not supported for order type " + command.type());
        }
        if (command.priceProtect() != null
                && hasText(command.type())
                && !capability.supportedPriceProtectOrderTypes().contains(command.type())) {
            errors.add("priceProtect is not supported for order type " + command.type());
        }
        if (hasPeggedOrderParameter(command)) {
            if (!hasText(command.pegPriceType())) {
                errors.add("pegged orders require pegPriceType");
            }
            if (hasText(command.type()) && !capability.supportedPeggedOrderTypes().contains(command.type())) {
                errors.add("pegged orders are not supported for order type " + command.type());
            }
            if (hasText(command.pegOffsetType()) != (command.pegOffsetValue() != null)) {
                errors.add("pegOffsetType and pegOffsetValue must be provided together");
            }
        }
        if (command.autoRepayAtCancel() != null
                && !capability.autoRepayAtCancelSideEffectTypes().contains(command.sideEffectType())) {
            errors.add("autoRepayAtCancel requires a configured borrow sideEffectType");
        }
        if ("GTD".equals(command.timeInForce()) && command.goodTillDate() == null) {
            errors.add("GTD orders require goodTillDate");
        }
        if (command.goodTillDate() != null && !"GTD".equals(command.timeInForce())) {
            errors.add("goodTillDate requires timeInForce GTD");
        }
        if (Boolean.TRUE.equals(command.reduceOnly()) && isHedgeModePositionSide(command.positionSide())) {
            errors.add("reduceOnly is not supported in hedge-mode orders");
        }
        if (Boolean.TRUE.equals(command.closePosition())) {
            if (Boolean.TRUE.equals(command.reduceOnly())) {
                errors.add("closePosition must not be combined with reduceOnly");
            }
            if (hasText(command.type()) && !FUTURES_MARKET_STOP_TYPES.contains(command.type())) {
                errors.add("closePosition is only supported for STOP_MARKET and TAKE_PROFIT_MARKET orders");
            }
        }
        if (command.postOnly() != null && hasText(command.type()) && !"LIMIT".equals(command.type())) {
            errors.add("postOnly is only supported for LIMIT orders");
        }
    }

    private static void validateOrderTypeParameters(BinanceOrderCommand command,
                                                    BinanceTradingCapability capability,
                                                    List<String> errors) {
        String type = command.type();
        if (!hasText(type)) {
            return;
        }

        if ("LIMIT".equals(type)) {
            requireQuantity(command, errors);
            requirePriceUnlessPriceMatchOrPegged(command, capability, errors);
            requireTimeInForce(command, capability, errors);
        } else if ("MARKET".equals(type)) {
            requireMarketQuantity(command, capability, errors);
        } else if ("LIMIT_MAKER".equals(type)) {
            requireQuantity(command, errors);
            requirePriceUnlessPegged(command, capability, errors);
        } else if (SPOT_MARKET_STOP_TYPES.contains(type)) {
            requireQuantity(command, errors);
            requireStopPriceOrTrailingDelta(command, errors);
        } else if (SPOT_LIMIT_STOP_TYPES.contains(type)) {
            requireQuantity(command, errors);
            requirePriceUnlessPegged(command, capability, errors);
            requireTimeInForce(command, capability, errors);
            requireStopPriceOrTrailingDelta(command, errors);
        } else if (FUTURES_LIMIT_STOP_TYPES.contains(type)) {
            requireQuantity(command, errors);
            requirePriceUnlessPriceMatchOrPegged(command, capability, errors);
            requireStopPrice(command, errors);
        } else if (FUTURES_MARKET_STOP_TYPES.contains(type)) {
            requireStopPrice(command, errors);
            requireQuantityUnlessClosePosition(command, errors);
        } else if ("TRAILING_STOP_MARKET".equals(type)) {
            requireCallbackRate(command, errors);
            requireQuantity(command, errors);
        }
    }

    private static void requireMarketQuantity(BinanceOrderCommand command,
                                              BinanceTradingCapability capability,
                                              List<String> errors) {
        boolean hasQuantity = hasValue(command.quantity());
        boolean hasQuoteQuantity = capability.supportsQuoteOrderQty() && hasValue(command.quoteOrderQty());
        if (!hasQuantity && !hasQuoteQuantity) {
            errors.add(capability.supportsQuoteOrderQty()
                    ? "MARKET orders require quantity or quoteOrderQty"
                    : "MARKET orders require quantity");
        }
        if (hasQuantity && hasQuoteQuantity) {
            errors.add("MARKET orders must not provide both quantity and quoteOrderQty");
        }
    }

    private static void requireQuantityUnlessClosePosition(BinanceOrderCommand command, List<String> errors) {
        if (Boolean.TRUE.equals(command.closePosition())) {
            if (hasValue(command.quantity())) {
                errors.add("closePosition orders must not provide quantity");
            }
        } else {
            requireQuantity(command, errors);
        }
    }

    private static void requireQuantity(BinanceOrderCommand command, List<String> errors) {
        if (!hasValue(command.quantity())) {
            errors.add(command.type() + " orders require quantity");
        }
    }

    private static void requirePrice(BinanceOrderCommand command, List<String> errors) {
        if (!hasValue(command.price())) {
            errors.add(command.type() + " orders require price");
        }
    }

    private static void requirePriceUnlessPriceMatchOrPegged(BinanceOrderCommand command,
                                                             BinanceTradingCapability capability,
                                                             List<String> errors) {
        boolean hasSupportedPriceMatch = capability.supportsPriceMatch() && hasText(command.priceMatch());
        boolean hasSupportedPeg = capability.supportsPeggedOrders() && hasText(command.pegPriceType());
        if (!hasValue(command.price()) && !hasSupportedPriceMatch && !hasSupportedPeg) {
            errors.add(command.type() + " orders require " + priceFieldAlternatives(capability));
        }
    }

    private static void requirePriceUnlessPegged(BinanceOrderCommand command,
                                                 BinanceTradingCapability capability,
                                                 List<String> errors) {
        boolean hasSupportedPeg = capability.supportsPeggedOrders() && hasText(command.pegPriceType());
        if (!hasValue(command.price()) && !hasSupportedPeg) {
            errors.add(command.type() + " orders require " + priceFieldAlternatives(capability));
        }
    }

    private static String priceFieldAlternatives(BinanceTradingCapability capability) {
        if (capability.supportsPriceMatch() && capability.supportsPeggedOrders()) {
            return "price, priceMatch, or pegPriceType";
        }
        if (capability.supportsPriceMatch()) {
            return "price or priceMatch";
        }
        if (capability.supportsPeggedOrders()) {
            return "price or pegPriceType";
        }
        return "price";
    }

    private static void requireStopPrice(BinanceOrderCommand command, List<String> errors) {
        if (!hasValue(command.stopPrice())) {
            errors.add(command.type() + " orders require stopPrice");
        }
    }

    private static void requireStopPriceOrTrailingDelta(BinanceOrderCommand command, List<String> errors) {
        if (!hasValue(command.stopPrice()) && !hasValue(command.trailingDelta())) {
            errors.add(command.type() + " orders require stopPrice or trailingDelta");
        }
    }

    private static void requireCallbackRate(BinanceOrderCommand command, List<String> errors) {
        if (!hasValue(command.callbackRate())) {
            errors.add("TRAILING_STOP_MARKET orders require callbackRate");
        }
    }

    private static void requireTimeInForce(BinanceOrderCommand command,
                                           BinanceTradingCapability capability,
                                           List<String> errors) {
        requireOneOf("timeInForce", command.timeInForce(), capability.supportedTimeInForce(), errors);
    }

    private static void requireText(String field, String value, List<String> errors) {
        if (!hasText(value)) {
            errors.add(field + " is required");
        }
    }

    private static void requireOneOf(String field, String value, Set<String> allowed, List<String> errors) {
        requireText(field, value, errors);
        if (hasText(value) && !allowed.contains(value)) {
            errors.add(field + " must be one of " + allowed);
        }
    }

    private static void requireOptionalOneOf(String field, String value, Set<String> allowed, List<String> errors) {
        if (hasText(value) && !allowed.contains(value)) {
            errors.add(field + " must be one of " + allowed);
        }
    }

    private static void requirePositiveWhenPresent(String field, BigDecimal value, List<String> errors) {
        if (value != null && value.signum() <= 0) {
            errors.add(field + " must be positive when provided");
        }
    }

    private static void requirePositiveWhenPresent(String field, Long value, List<String> errors) {
        if (value != null && value <= 0) {
            errors.add(field + " must be positive when provided");
        }
    }

    private static void requirePositiveWhenPresent(String field, Integer value, List<String> errors) {
        if (value != null && value <= 0) {
            errors.add(field + " must be positive when provided");
        }
    }

    private static boolean hasValue(BigDecimal value) {
        return value != null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hasPeggedOrderParameter(BinanceOrderCommand command) {
        return hasText(command.pegPriceType()) || hasText(command.pegOffsetType()) || command.pegOffsetValue() != null;
    }

    private static boolean isHedgeModePositionSide(String value) {
        return "LONG".equals(value) || "SHORT".equals(value);
    }
}
