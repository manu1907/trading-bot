package io.github.manu.exchange.binance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceMarketTypeTest {

    @Test
    void covers_binance_trading_product_families() {
        assertThat(BinanceMarketType.SPOT.exchangeInfoPath()).isEqualTo("/api/v3/exchangeInfo");
        assertThat(BinanceMarketType.MARGIN_CROSS.exchangeInfoPath()).isEqualTo("/api/v3/exchangeInfo");
        assertThat(BinanceMarketType.MARGIN_ISOLATED.exchangeInfoPath()).isEqualTo("/api/v3/exchangeInfo");
        assertThat(BinanceMarketType.FUTURES_USD_M.exchangeInfoPath()).isEqualTo("/fapi/v1/exchangeInfo");
        assertThat(BinanceMarketType.FUTURES_COIN_M.exchangeInfoPath()).isEqualTo("/dapi/v1/exchangeInfo");
        assertThat(BinanceMarketType.OPTIONS.exchangeInfoPath()).isEqualTo("/eapi/v1/exchangeInfo");
    }

    @Test
    void identifies_futures_families_that_need_futures_account_expectations() {
        assertThat(BinanceMarketType.FUTURES_USD_M.futures()).isTrue();
        assertThat(BinanceMarketType.FUTURES_COIN_M.futures()).isTrue();
        assertThat(BinanceMarketType.SPOT.futures()).isFalse();
        assertThat(BinanceMarketType.OPTIONS.futures()).isFalse();
    }

    @Test
    void maps_documented_user_data_start_paths() {
        assertThat(BinanceMarketType.SPOT.userDataStartPath()).isNull();
        assertThat(BinanceMarketType.MARGIN_CROSS.userDataStartPath()).isEqualTo("/sapi/v1/userListenToken");
        assertThat(BinanceMarketType.MARGIN_ISOLATED.userDataStartPath()).isEqualTo("/sapi/v1/userListenToken");
        assertThat(BinanceMarketType.FUTURES_USD_M.userDataStartPath()).isEqualTo("/fapi/v1/listenKey");
        assertThat(BinanceMarketType.FUTURES_COIN_M.userDataStartPath()).isEqualTo("/dapi/v1/listenKey");
        assertThat(BinanceMarketType.OPTIONS.userDataStartPath()).isEqualTo("/eapi/v1/listenKey");
    }
}
