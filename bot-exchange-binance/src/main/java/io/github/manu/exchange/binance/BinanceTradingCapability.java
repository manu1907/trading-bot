package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;

import java.util.Set;

record BinanceTradingCapability(
        String newOrderPath,
        String testOrderPath,
        String queryOrderPath,
        String cancelOrderPath,
        String openOrdersPath,
        Set<String> supportedSides,
        Set<String> supportedOrderTypes,
        Set<String> supportedTimeInForce,
        Set<String> supportedOrderResponseTypes,
        Set<String> supportedSelfTradePreventionModes,
        Set<String> supportedPositionSides,
        boolean supportsQuoteOrderQty,
        boolean supportsReduceOnly,
        boolean supportsClosePosition,
        boolean supportsPriceMatch,
        boolean supportsPeggedOrders,
        boolean supportsIcebergQty,
        boolean supportsTrailingDelta,
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
    private static final Set<String> OPTIONS_STP_MODES = Set.of("EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH");
    private static final Set<String> NO_POSITION_SIDE = Set.of("NONE");
    private static final Set<String> FUTURES_POSITION_SIDES = Set.of("BOTH", "LONG", "SHORT");

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
                Set.copyOf(trading.supportedSides()),
                Set.copyOf(trading.supportedOrderTypes()),
                Set.copyOf(trading.supportedTimeInForce()),
                Set.copyOf(trading.supportedOrderResponseTypes()),
                Set.copyOf(trading.supportedSelfTradePreventionModes()),
                Set.copyOf(trading.supportedPositionSides()),
                trading.supportsQuoteOrderQty(),
                trading.supportsReduceOnly(),
                trading.supportsClosePosition(),
                trading.supportsPriceMatch(),
                trading.supportsPeggedOrders(),
                trading.supportsIcebergQty(),
                trading.supportsTrailingDelta(),
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
                BUY_SELL,
                SPOT_MARGIN_ORDER_TYPES,
                SPOT_TIME_IN_FORCE,
                ACK_RESULT_FULL,
                STP_MODES,
                NO_POSITION_SIDE,
                true,
                false,
                false,
                false,
                true,
                true,
                true,
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
                BUY_SELL,
                SPOT_MARGIN_ORDER_TYPES,
                SPOT_TIME_IN_FORCE,
                ACK_RESULT_FULL,
                STP_MODES,
                NO_POSITION_SIDE,
                true,
                false,
                false,
                false,
                false,
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
                BUY_SELL,
                FUTURES_ORDER_TYPES,
                FUTURES_TIME_IN_FORCE,
                ACK_RESULT,
                STP_MODES,
                FUTURES_POSITION_SIDES,
                false,
                true,
                true,
                true,
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
                BUY_SELL,
                Set.of("LIMIT"),
                OPTIONS_TIME_IN_FORCE,
                ACK_RESULT,
                OPTIONS_STP_MODES,
                NO_POSITION_SIDE,
                false,
                true,
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
