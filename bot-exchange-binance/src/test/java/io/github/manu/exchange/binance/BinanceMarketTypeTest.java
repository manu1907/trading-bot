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
}
