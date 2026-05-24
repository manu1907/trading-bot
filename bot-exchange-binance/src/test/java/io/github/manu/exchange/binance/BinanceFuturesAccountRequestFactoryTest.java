package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceFuturesAccountRequestFactoryTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_499_827_319_559L),
            ZoneOffset.UTC
    );

    @Test
    void builds_position_mode_request() {
        BinanceFuturesAccountRequestFactory factory = new BinanceFuturesAccountRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.changePositionMode("HEDGE", "test-secret");

        assertThat(request.payload())
                .isEqualTo("dualSidePosition=true&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString())
                .startsWith("https://demo-fapi.binance.com/fapi/v1/positionSide/dual?");
    }

    @Test
    void builds_margin_type_request() {
        BinanceFuturesAccountRequestFactory factory = new BinanceFuturesAccountRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.changeMarginType("BTCUSDT", "ISOLATED", "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&marginType=ISOLATED&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/marginType?");
    }

    @Test
    void builds_initial_leverage_request() {
        BinanceFuturesAccountRequestFactory factory = new BinanceFuturesAccountRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.changeInitialLeverage("BTCUSDT", 21, "test-secret");

        assertThat(request.payload()).isEqualTo("symbol=BTCUSDT&leverage=21&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/leverage?");
    }

    @Test
    void builds_multi_assets_mode_request_for_usdm() {
        BinanceFuturesAccountRequestFactory factory = new BinanceFuturesAccountRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.changeMultiAssetsMode(false, "test-secret");

        assertThat(request.payload()).isEqualTo("multiAssetsMargin=false&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/multiAssetsMargin?");
    }

    @Test
    void rejects_leverage_outside_configured_limits() {
        BinanceFuturesAccountRequestFactory factory = new BinanceFuturesAccountRequestFactory(binance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> factory.changeInitialLeverage("BTCUSDT", 126, "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leverage must be between 1 and 125");
    }

    @Test
    void rejects_multi_assets_mode_when_not_supported() {
        BinanceFuturesAccountRequestFactory factory = new BinanceFuturesAccountRequestFactory(coinMBinance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> factory.changeMultiAssetsMode(true, "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multi-assets mode is not supported");
    }

    private BinanceProperties binance() {
        return binance("FUTURES_USD_M", "https://demo-fapi.binance.com", "/fapi/v1", "/fapi/v1/multiAssetsMargin");
    }

    private BinanceProperties coinMBinance() {
        return binance("FUTURES_COIN_M", "https://demo-dapi.binance.com", "/dapi/v1", null);
    }

    private BinanceProperties binance(String marketType, String baseUrl, String pathPrefix, String multiAssetsModePath) {
        return new BinanceProperties(
                marketType,
                new BinanceProperties.Credentials(
                        "binance_demo_main",
                        "api-key",
                        "api-secret",
                        "HMAC_SHA256",
                        List.of("USER_STREAM", "USER_DATA", "TRADE")
                ),
                rest(baseUrl, pathPrefix),
                websocket(),
                trading(pathPrefix),
                userData(pathPrefix),
                futuresAccount(pathPrefix, multiAssetsModePath)
        );
    }

    private BinanceProperties.Rest rest(String baseUrl, String pathPrefix) {
        return new BinanceProperties.Rest(
                baseUrl,
                pathPrefix + "/exchangeInfo",
                pathPrefix + "/time",
                "X-MBX-APIKEY",
                "HMAC_SHA256",
                "MILLISECONDS",
                5000,
                2000,
                5000,
                3,
                200,
                List.of(408, 429, 500, 502, 503, 504),
                List.of("X-MBX-USED-WEIGHT"),
                List.of("X-MBX-ORDER-COUNT"),
                "RESULT",
                new BinanceProperties.UnknownExecutionStatus(
                        List.of("Unknown error, please check your request or try again later."),
                        List.of(503)
                )
        );
    }

    private BinanceProperties.Websocket websocket() {
        return new BinanceProperties.Websocket(
                "wss://fstream.binancefuture.com",
                "/public",
                "/market",
                "/private",
                "/ws",
                "/stream",
                24,
                10,
                null,
                3,
                null,
                10,
                10,
                1024,
                null,
                "MILLISECONDS"
        );
    }

    private BinanceProperties.Trading trading(String pathPrefix) {
        return new BinanceProperties.Trading(
                pathPrefix + "/order",
                null,
                pathPrefix + "/order",
                pathPrefix + "/order",
                pathPrefix + "/openOrders",
                List.of("BUY", "SELL"),
                List.of("LIMIT", "MARKET", "STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET", "TRAILING_STOP_MARKET"),
                List.of("GTC", "IOC", "FOK", "GTX", "GTD"),
                List.of("ACK", "RESULT"),
                List.of("EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH"),
                List.of("BOTH", "LONG", "SHORT"),
                List.of("LIMIT", "STOP", "TAKE_PROFIT"),
                List.of("STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET", "TRAILING_STOP_MARKET"),
                List.of("MARK_PRICE", "CONTRACT_PRICE"),
                List.of("STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
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

    private BinanceProperties.UserDataStream userData(String pathPrefix) {
        return new BinanceProperties.UserDataStream(
                "listen_key",
                pathPrefix + "/listenKey",
                pathPrefix + "/listenKey",
                pathPrefix + "/listenKey",
                60,
                30,
                1
        );
    }

    private BinanceProperties.FuturesAccount futuresAccount(String pathPrefix, String multiAssetsModePath) {
        return new BinanceProperties.FuturesAccount(
                "ONE_WAY",
                List.of("ONE_WAY", "HEDGE"),
                pathPrefix + "/positionSide/dual",
                pathPrefix + "/marginType",
                pathPrefix + "/leverage",
                multiAssetsModePath,
                1,
                125,
                List.of("CROSSED", "ISOLATED"),
                false,
                false
        );
    }
}
