package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;

import java.util.Set;

record BinanceTradingCapability(
        String newOrderPath,
        String testOrderPath,
        String queryOrderPath,
        String cancelOrderPath,
        String openOrdersPath,
        String allOrdersPath,
        String accountTradesPath,
        String commissionRatesPath,
        String preventedMatchesPath,
        String amendKeepPriorityPath,
        String cancelReplacePath,
        String batchOrdersPath,
        String modifyOrderPath,
        String modifyMultipleOrdersPath,
        String modifyOrderHistoryPath,
        String cancelMultipleOrdersPath,
        String cancelAllOpenOrdersPath,
        String countdownCancelAllPath,
        Set<String> supportedSides,
        Set<String> supportedOrderTypes,
        Set<String> supportedTimeInForce,
        Set<String> supportedOrderResponseTypes,
        Set<String> supportedSelfTradePreventionModes,
        Set<String> supportedPositionSides,
        Set<String> supportedPriceMatchOrderTypes,
        Set<String> supportedWorkingTypeOrderTypes,
        Set<String> supportedWorkingTypes,
        Set<String> supportedPriceProtectOrderTypes,
        Set<String> supportedPeggedOrderTypes,
        Set<String> supportedPegPriceTypes,
        Set<String> supportedPegOffsetTypes,
        Set<String> supportedMarginSideEffectTypes,
        Set<String> autoRepayAtCancelSideEffectTypes,
        Integer maxPegOffsetValue,
        boolean supportsQuoteOrderQty,
        boolean supportsReduceOnly,
        boolean supportsClosePosition,
        boolean supportsPriceMatch,
        boolean supportsWorkingType,
        boolean supportsPriceProtect,
        boolean supportsPeggedOrders,
        boolean supportsIcebergQty,
        boolean supportsTrailingDelta,
        boolean supportsMarginSideEffectControls,
        boolean supportsIsolatedMarginFlag,
        boolean supportsMarketMakerProtection
) {

    private static final Set<String> BUY_SELL = Set.of("BUY", "SELL");
    private static final Set<String> ACK_RESULT_FULL = Set.of("ACK", "RESULT", "FULL");
    private static final Set<String> ACK_RESULT = Set.of("ACK", "RESULT");
    private static final Set<String> SPOT_MARGIN_ORDER_TYPES = Set.of(
            "LIMIT",
            "MARKET",
            "STOP_LOSS",
            "STOP_LOSS_LIMIT",
            "TAKE_PROFIT",
            "TAKE_PROFIT_LIMIT",
            "LIMIT_MAKER"
    );
    private static final Set<String> FUTURES_ORDER_TYPES = Set.of(
            "LIMIT",
            "MARKET",
            "STOP",
            "STOP_MARKET",
            "TAKE_PROFIT",
            "TAKE_PROFIT_MARKET",
            "TRAILING_STOP_MARKET"
    );
    private static final Set<String> SPOT_TIME_IN_FORCE = Set.of("GTC", "IOC", "FOK");
    private static final Set<String> FUTURES_TIME_IN_FORCE = Set.of("GTC", "IOC", "FOK", "GTX", "GTD");
    private static final Set<String> OPTIONS_TIME_IN_FORCE = Set.of("GTC");
    private static final Set<String> STP_MODES = Set.of("NONE", "EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH");
    private static final Set<String> FUTURES_STP_MODES = Set.of("EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH");
    private static final Set<String> OPTIONS_STP_MODES = Set.of("EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH");
    private static final Set<String> NO_POSITION_SIDE = Set.of("NONE");
    private static final Set<String> FUTURES_POSITION_SIDES = Set.of("BOTH", "LONG", "SHORT");
    private static final Set<String> NO_VALUES = Set.of();
    private static final Set<String> FUTURES_PRICE_MATCH_ORDER_TYPES = Set.of("LIMIT", "STOP", "TAKE_PROFIT");
    private static final Set<String> FUTURES_WORKING_TYPE_ORDER_TYPES = Set.of(
            "STOP",
            "STOP_MARKET",
            "TAKE_PROFIT",
            "TAKE_PROFIT_MARKET",
            "TRAILING_STOP_MARKET"
    );
    private static final Set<String> FUTURES_WORKING_TYPES = Set.of("MARK_PRICE", "CONTRACT_PRICE");
    private static final Set<String> FUTURES_PRICE_PROTECT_ORDER_TYPES = Set.of(
            "STOP",
            "STOP_MARKET",
            "TAKE_PROFIT",
            "TAKE_PROFIT_MARKET"
    );
    private static final Set<String> SPOT_PEGGED_ORDER_TYPES = Set.of(
            "LIMIT",
            "LIMIT_MAKER",
            "STOP_LOSS_LIMIT",
            "TAKE_PROFIT_LIMIT"
    );
    private static final Set<String> SPOT_PEG_PRICE_TYPES = Set.of("PRIMARY_PEG", "MARKET_PEG");
    private static final Set<String> SPOT_PEG_OFFSET_TYPES = Set.of("PRICE_LEVEL");
    private static final Set<String> MARGIN_SIDE_EFFECT_TYPES = Set.of(
            "NO_SIDE_EFFECT",
            "MARGIN_BUY",
            "AUTO_REPAY",
            "AUTO_BORROW_REPAY"
    );
    private static final Set<String> AUTO_REPAY_AT_CANCEL_SIDE_EFFECT_TYPES = Set.of(
            "MARGIN_BUY",
            "AUTO_BORROW_REPAY"
    );

    static BinanceTradingCapability forMarketType(BinanceMarketType marketType) {
        return switch (marketType) {
            case SPOT -> spot();
            case MARGIN_CROSS -> margin(false);
            case MARGIN_ISOLATED -> margin(true);
            case FUTURES_USD_M -> futures("/fapi/v1/order");
            case FUTURES_COIN_M -> futures("/dapi/v1/order");
            case OPTIONS -> options();
        };
    }

    static BinanceTradingCapability fromConfig(BinanceProperties.Trading trading) {
        return new BinanceTradingCapability(
                trading.newOrderPath(),
                trading.testOrderPath(),
                trading.queryOrderPath(),
                trading.cancelOrderPath(),
                trading.openOrdersPath(),
                trading.allOrdersPath(),
                trading.accountTradesPath(),
                trading.commissionRatesPath(),
                trading.preventedMatchesPath(),
                trading.amendKeepPriorityPath(),
                trading.cancelReplacePath(),
                trading.batchOrdersPath(),
                trading.modifyOrderPath(),
                trading.modifyMultipleOrdersPath(),
                trading.modifyOrderHistoryPath(),
                trading.cancelMultipleOrdersPath(),
                trading.cancelAllOpenOrdersPath(),
                trading.countdownCancelAllPath(),
                Set.copyOf(trading.supportedSides()),
                Set.copyOf(trading.supportedOrderTypes()),
                Set.copyOf(trading.supportedTimeInForce()),
                Set.copyOf(trading.supportedOrderResponseTypes()),
                Set.copyOf(trading.supportedSelfTradePreventionModes()),
                Set.copyOf(trading.supportedPositionSides()),
                Set.copyOf(trading.supportedPriceMatchOrderTypes()),
                Set.copyOf(trading.supportedWorkingTypeOrderTypes()),
                Set.copyOf(trading.supportedWorkingTypes()),
                Set.copyOf(trading.supportedPriceProtectOrderTypes()),
                Set.copyOf(trading.supportedPeggedOrderTypes()),
                Set.copyOf(trading.supportedPegPriceTypes()),
                Set.copyOf(trading.supportedPegOffsetTypes()),
                Set.copyOf(trading.supportedMarginSideEffectTypes()),
                Set.copyOf(trading.autoRepayAtCancelSideEffectTypes()),
                trading.maxPegOffsetValue(),
                trading.supportsQuoteOrderQty(),
                trading.supportsReduceOnly(),
                trading.supportsClosePosition(),
                trading.supportsPriceMatch(),
                trading.supportsWorkingType(),
                trading.supportsPriceProtect(),
                trading.supportsPeggedOrders(),
                trading.supportsIcebergQty(),
                trading.supportsTrailingDelta(),
                trading.supportsMarginSideEffectControls(),
                trading.supportsIsolatedMarginFlag(),
                trading.supportsMarketMakerProtection()
        );
    }

    private static BinanceTradingCapability spot() {
        return new BinanceTradingCapability(
                "/api/v3/order",
                "/api/v3/order/test",
                "/api/v3/order",
                "/api/v3/order",
                "/api/v3/openOrders",
                "/api/v3/allOrders",
                "/api/v3/myTrades",
                "/api/v3/account/commission",
                "/api/v3/myPreventedMatches",
                "/api/v3/order/amend/keepPriority",
                "/api/v3/order/cancelReplace",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                BUY_SELL,
                SPOT_MARGIN_ORDER_TYPES,
                SPOT_TIME_IN_FORCE,
                ACK_RESULT_FULL,
                STP_MODES,
                NO_POSITION_SIDE,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                SPOT_PEGGED_ORDER_TYPES,
                SPOT_PEG_PRICE_TYPES,
                SPOT_PEG_OFFSET_TYPES,
                NO_VALUES,
                NO_VALUES,
                100,
                true,
                false,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                false,
                false,
                false
        );
    }

    private static BinanceTradingCapability margin(boolean isolated) {
        return new BinanceTradingCapability(
                "/sapi/v1/margin/order",
                null,
                "/sapi/v1/margin/order",
                "/sapi/v1/margin/order",
                "/sapi/v1/margin/openOrders",
                "/sapi/v1/margin/allOrders",
                "/sapi/v1/margin/myTrades",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                BUY_SELL,
                SPOT_MARGIN_ORDER_TYPES,
                SPOT_TIME_IN_FORCE,
                ACK_RESULT_FULL,
                STP_MODES,
                NO_POSITION_SIDE,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                MARGIN_SIDE_EFFECT_TYPES,
                AUTO_REPAY_AT_CANCEL_SIDE_EFFECT_TYPES,
                null,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                isolated,
                false
        );
    }

    private static BinanceTradingCapability futures(String orderPath) {
        return new BinanceTradingCapability(
                orderPath,
                null,
                orderPath,
                orderPath,
                orderPath.replace("/order", "/openOrders"),
                orderPath.replace("/order", "/allOrders"),
                orderPath.replace("/order", "/userTrades"),
                null,
                null,
                null,
                null,
                orderPath.replace("/order", "/batchOrders"),
                orderPath,
                orderPath.replace("/order", "/batchOrders"),
                orderPath.replace("/order", "/orderAmendment"),
                orderPath.replace("/order", "/batchOrders"),
                orderPath.replace("/order", "/allOpenOrders"),
                orderPath.replace("/order", "/countdownCancelAll"),
                BUY_SELL,
                FUTURES_ORDER_TYPES,
                FUTURES_TIME_IN_FORCE,
                ACK_RESULT,
                FUTURES_STP_MODES,
                FUTURES_POSITION_SIDES,
                FUTURES_PRICE_MATCH_ORDER_TYPES,
                FUTURES_WORKING_TYPE_ORDER_TYPES,
                FUTURES_WORKING_TYPES,
                FUTURES_PRICE_PROTECT_ORDER_TYPES,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                null,
                false,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                false
        );
    }

    private static BinanceTradingCapability options() {
        return new BinanceTradingCapability(
                "/eapi/v1/order",
                null,
                "/eapi/v1/order",
                "/eapi/v1/order",
                "/eapi/v1/openOrders",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                BUY_SELL,
                Set.of("LIMIT"),
                OPTIONS_TIME_IN_FORCE,
                ACK_RESULT,
                OPTIONS_STP_MODES,
                NO_POSITION_SIDE,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                NO_VALUES,
                null,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true
        );
    }
}
