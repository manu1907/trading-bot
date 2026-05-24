package io.github.manu.exchange.binance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceTradingCapabilityTest {

    @Test
    void maps_documented_new_order_paths_by_market_type() {
        assertThat(BinanceTradingCapability.forMarketType(BinanceMarketType.SPOT).newOrderPath())
                .isEqualTo("/api/v3/order");
        assertThat(BinanceTradingCapability.forMarketType(BinanceMarketType.MARGIN_CROSS).newOrderPath())
                .isEqualTo("/sapi/v1/margin/order");
        assertThat(BinanceTradingCapability.forMarketType(BinanceMarketType.MARGIN_ISOLATED).newOrderPath())
                .isEqualTo("/sapi/v1/margin/order");
        assertThat(BinanceTradingCapability.forMarketType(BinanceMarketType.FUTURES_USD_M).newOrderPath())
                .isEqualTo("/fapi/v1/order");
        assertThat(BinanceTradingCapability.forMarketType(BinanceMarketType.FUTURES_COIN_M).newOrderPath())
                .isEqualTo("/dapi/v1/order");
        assertThat(BinanceTradingCapability.forMarketType(BinanceMarketType.OPTIONS).newOrderPath())
                .isEqualTo("/eapi/v1/order");
    }

    @Test
    void maps_documented_order_and_trade_history_paths_by_market_type() {
        BinanceTradingCapability spot = BinanceTradingCapability.forMarketType(BinanceMarketType.SPOT);
        BinanceTradingCapability margin = BinanceTradingCapability.forMarketType(BinanceMarketType.MARGIN_CROSS);
        BinanceTradingCapability usdm = BinanceTradingCapability.forMarketType(BinanceMarketType.FUTURES_USD_M);
        BinanceTradingCapability coinm = BinanceTradingCapability.forMarketType(BinanceMarketType.FUTURES_COIN_M);
        BinanceTradingCapability options = BinanceTradingCapability.forMarketType(BinanceMarketType.OPTIONS);

        assertThat(spot.allOrdersPath()).isEqualTo("/api/v3/allOrders");
        assertThat(spot.accountTradesPath()).isEqualTo("/api/v3/myTrades");
        assertThat(margin.allOrdersPath()).isEqualTo("/sapi/v1/margin/allOrders");
        assertThat(margin.accountTradesPath()).isEqualTo("/sapi/v1/margin/myTrades");
        assertThat(margin.batchOrdersPath()).isNull();
        assertThat(margin.modifyOrderPath()).isNull();
        assertThat(usdm.allOrdersPath()).isEqualTo("/fapi/v1/allOrders");
        assertThat(usdm.accountTradesPath()).isEqualTo("/fapi/v1/userTrades");
        assertThat(usdm.batchOrdersPath()).isEqualTo("/fapi/v1/batchOrders");
        assertThat(usdm.modifyOrderPath()).isEqualTo("/fapi/v1/order");
        assertThat(coinm.allOrdersPath()).isEqualTo("/dapi/v1/allOrders");
        assertThat(coinm.accountTradesPath()).isEqualTo("/dapi/v1/userTrades");
        assertThat(coinm.batchOrdersPath()).isEqualTo("/dapi/v1/batchOrders");
        assertThat(coinm.modifyOrderPath()).isEqualTo("/dapi/v1/order");
        assertThat(options.allOrdersPath()).isNull();
        assertThat(options.accountTradesPath()).isNull();
        assertThat(options.batchOrdersPath()).isNull();
        assertThat(options.modifyOrderPath()).isNull();
    }

    @Test
    void distinguishes_market_specific_order_features() {
        BinanceTradingCapability spot = BinanceTradingCapability.forMarketType(BinanceMarketType.SPOT);
        BinanceTradingCapability usdm = BinanceTradingCapability.forMarketType(BinanceMarketType.FUTURES_USD_M);
        BinanceTradingCapability options = BinanceTradingCapability.forMarketType(BinanceMarketType.OPTIONS);

        assertThat(spot.supportsQuoteOrderQty()).isTrue();
        assertThat(spot.supportsPeggedOrders()).isTrue();
        assertThat(usdm.supportedPositionSides()).containsExactlyInAnyOrder("BOTH", "LONG", "SHORT");
        assertThat(usdm.supportsPriceMatch()).isTrue();
        assertThat(options.supportedOrderTypes()).containsExactly("LIMIT");
        assertThat(options.supportsMarketMakerProtection()).isTrue();
    }
}
