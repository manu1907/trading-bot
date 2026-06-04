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
        assertThat(spot.commissionRatesPath()).isEqualTo("/api/v3/account/commission");
        assertThat(spot.preventedMatchesPath()).isEqualTo("/api/v3/myPreventedMatches");
        assertThat(spot.amendKeepPriorityPath()).isEqualTo("/api/v3/order/amend/keepPriority");
        assertThat(spot.cancelReplacePath()).isEqualTo("/api/v3/order/cancelReplace");
        assertThat(spot.sorOrderPath()).isEqualTo("/api/v3/sor/order");
        assertThat(spot.sorTestOrderPath()).isEqualTo("/api/v3/sor/order/test");
        assertThat(spot.orderListOcoPath()).isEqualTo("/api/v3/orderList/oco");
        assertThat(spot.orderListOtoPath()).isEqualTo("/api/v3/orderList/oto");
        assertThat(spot.orderListOtocoPath()).isEqualTo("/api/v3/orderList/otoco");
        assertThat(spot.orderListOpoPath()).isEqualTo("/api/v3/orderList/opo");
        assertThat(spot.orderListOpocoPath()).isEqualTo("/api/v3/orderList/opoco");
        assertThat(margin.allOrdersPath()).isEqualTo("/sapi/v1/margin/allOrders");
        assertThat(margin.accountTradesPath()).isEqualTo("/sapi/v1/margin/myTrades");
        assertThat(margin.commissionRatesPath()).isNull();
        assertThat(margin.preventedMatchesPath()).isEqualTo("/sapi/v1/margin/myPreventedMatches");
        assertThat(margin.amendKeepPriorityPath()).isNull();
        assertThat(margin.cancelReplacePath()).isNull();
        assertThat(margin.sorOrderPath()).isNull();
        assertThat(margin.sorTestOrderPath()).isNull();
        assertThat(margin.orderListOcoPath()).isEqualTo("/sapi/v1/margin/order/oco");
        assertThat(margin.orderListOtoPath()).isEqualTo("/sapi/v1/margin/order/oto");
        assertThat(margin.orderListOtocoPath()).isEqualTo("/sapi/v1/margin/order/otoco");
        assertThat(margin.orderListOpoPath()).isNull();
        assertThat(margin.orderListOpocoPath()).isNull();
        assertThat(margin.batchOrdersPath()).isNull();
        assertThat(margin.modifyOrderPath()).isNull();
        assertThat(margin.modifyMultipleOrdersPath()).isNull();
        assertThat(margin.modifyOrderHistoryPath()).isNull();
        assertThat(usdm.allOrdersPath()).isEqualTo("/fapi/v1/allOrders");
        assertThat(usdm.accountTradesPath()).isEqualTo("/fapi/v1/userTrades");
        assertThat(usdm.commissionRatesPath()).isNull();
        assertThat(usdm.preventedMatchesPath()).isNull();
        assertThat(usdm.amendKeepPriorityPath()).isNull();
        assertThat(usdm.cancelReplacePath()).isNull();
        assertThat(usdm.sorOrderPath()).isNull();
        assertThat(usdm.sorTestOrderPath()).isNull();
        assertThat(usdm.orderListOcoPath()).isNull();
        assertThat(usdm.orderListOtoPath()).isNull();
        assertThat(usdm.orderListOtocoPath()).isNull();
        assertThat(usdm.orderListOpoPath()).isNull();
        assertThat(usdm.orderListOpocoPath()).isNull();
        assertThat(usdm.batchOrdersPath()).isEqualTo("/fapi/v1/batchOrders");
        assertThat(usdm.modifyOrderPath()).isEqualTo("/fapi/v1/order");
        assertThat(usdm.modifyMultipleOrdersPath()).isEqualTo("/fapi/v1/batchOrders");
        assertThat(usdm.modifyOrderHistoryPath()).isEqualTo("/fapi/v1/orderAmendment");
        assertThat(coinm.allOrdersPath()).isEqualTo("/dapi/v1/allOrders");
        assertThat(coinm.accountTradesPath()).isEqualTo("/dapi/v1/userTrades");
        assertThat(coinm.commissionRatesPath()).isNull();
        assertThat(coinm.preventedMatchesPath()).isNull();
        assertThat(coinm.amendKeepPriorityPath()).isNull();
        assertThat(coinm.cancelReplacePath()).isNull();
        assertThat(coinm.sorOrderPath()).isNull();
        assertThat(coinm.sorTestOrderPath()).isNull();
        assertThat(coinm.orderListOcoPath()).isNull();
        assertThat(coinm.orderListOtoPath()).isNull();
        assertThat(coinm.orderListOtocoPath()).isNull();
        assertThat(coinm.orderListOpoPath()).isNull();
        assertThat(coinm.orderListOpocoPath()).isNull();
        assertThat(coinm.batchOrdersPath()).isEqualTo("/dapi/v1/batchOrders");
        assertThat(coinm.modifyOrderPath()).isEqualTo("/dapi/v1/order");
        assertThat(coinm.modifyMultipleOrdersPath()).isEqualTo("/dapi/v1/batchOrders");
        assertThat(coinm.modifyOrderHistoryPath()).isEqualTo("/dapi/v1/orderAmendment");
        assertThat(options.allOrdersPath()).isEqualTo("/eapi/v1/historyOrders");
        assertThat(options.accountTradesPath()).isEqualTo("/eapi/v1/userTrades");
        assertThat(options.commissionRatesPath()).isEqualTo("/eapi/v1/commission");
        assertThat(options.preventedMatchesPath()).isNull();
        assertThat(options.amendKeepPriorityPath()).isNull();
        assertThat(options.cancelReplacePath()).isNull();
        assertThat(options.sorOrderPath()).isNull();
        assertThat(options.sorTestOrderPath()).isNull();
        assertThat(options.orderListOcoPath()).isNull();
        assertThat(options.orderListOtoPath()).isNull();
        assertThat(options.orderListOtocoPath()).isNull();
        assertThat(options.orderListOpoPath()).isNull();
        assertThat(options.orderListOpocoPath()).isNull();
        assertThat(options.batchOrdersPath()).isEqualTo("/eapi/v1/batchOrders");
        assertThat(options.modifyOrderPath()).isNull();
        assertThat(options.modifyMultipleOrdersPath()).isNull();
        assertThat(options.modifyOrderHistoryPath()).isNull();
        assertThat(options.cancelMultipleOrdersPath()).isEqualTo("/eapi/v1/batchOrders");
        assertThat(options.cancelAllOpenOrdersPath()).isEqualTo("/eapi/v1/allOpenOrders");
        assertThat(options.cancelAllOpenOrdersByUnderlyingPath()).isEqualTo("/eapi/v1/allOpenOrdersByUnderlying");
        assertThat(options.countdownCancelAllPath()).isNull();
        assertThat(options.exerciseRecordPath()).isEqualTo("/eapi/v1/exerciseRecord");
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
