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
