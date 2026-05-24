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
    void builds_read_only_snapshot_requests_for_usdm() {
        BinanceFuturesAccountRequestFactory factory = new BinanceFuturesAccountRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest balances = factory.balances("test-secret");
        BinanceSignedRequest accountInfo = factory.accountInfo("test-secret");
        BinanceSignedRequest positionRisk = factory.positionRisk(
                new BinanceFuturesPositionRiskQuery("BTCUSDT", null, null),
                "test-secret"
        );

        assertThat(balances.payload()).isEqualTo("timestamp=1499827319559&recvWindow=5000");
        assertThat(balances.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v3/balance?");
        assertThat(accountInfo.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v3/account?");
        assertThat(positionRisk.payload()).isEqualTo("symbol=BTCUSDT&timestamp=1499827319559&recvWindow=5000");
        assertThat(positionRisk.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v3/positionRisk?");
    }

    @Test
    void builds_coin_m_position_risk_request_by_pair() {
        BinanceFuturesAccountRequestFactory factory = new BinanceFuturesAccountRequestFactory(coinMBinance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.positionRisk(
                new BinanceFuturesPositionRiskQuery(null, "BTCUSD", null),
                "test-secret"
        );

        assertThat(request.payload()).isEqualTo("pair=BTCUSD&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-dapi.binance.com/dapi/v1/positionRisk?");
    }

    @Test
    void builds_adl_quantile_and_force_order_requests() {
        BinanceFuturesAccountRequestFactory factory = new BinanceFuturesAccountRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest adlQuantiles = factory.adlQuantiles("BTCUSDT", "test-secret");
        BinanceSignedRequest forceOrders = factory.forceOrders(
                new BinanceFuturesForceOrderQuery("BTCUSDT", "LIQUIDATION", 1_700_000_000_000L, 1_700_001_000_000L, 100),
                "test-secret"
        );

        assertThat(adlQuantiles.payload()).isEqualTo("symbol=BTCUSDT&timestamp=1499827319559&recvWindow=5000");
        assertThat(adlQuantiles.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/adlQuantile?");
        assertThat(forceOrders.payload())
                .isEqualTo("symbol=BTCUSDT&autoCloseType=LIQUIDATION&startTime=1700000000000"
                        + "&endTime=1700001000000&limit=100&timestamp=1499827319559&recvWindow=5000");
        assertThat(forceOrders.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/forceOrders?");
    }

    @Test
    void builds_income_and_funding_rate_requests() {
        BinanceFuturesAccountRequestFactory factory = new BinanceFuturesAccountRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest income = factory.income(
                new BinanceFuturesIncomeQuery("BTCUSDT", "FUNDING_FEE", 1_700_000_000_000L, 1_700_001_000_000L, 2, 500),
                "test-secret"
        );
        String fundingRates = factory.fundingRates(
                new BinanceFuturesFundingRateQuery("BTCUSDT", 1_700_000_000_000L, 1_700_001_000_000L, 500)
        ).toString();

        assertThat(income.payload())
                .isEqualTo("symbol=BTCUSDT&incomeType=FUNDING_FEE&startTime=1700000000000"
                        + "&endTime=1700001000000&page=2&limit=500&timestamp=1499827319559&recvWindow=5000");
        assertThat(income.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/income?");
        assertThat(fundingRates)
                .isEqualTo("https://demo-fapi.binance.com/fapi/v1/fundingRate?symbol=BTCUSDT"
                        + "&startTime=1700000000000&endTime=1700001000000&limit=500");
    }

    @Test
    void requires_symbol_for_coin_m_funding_rate_history() {
        BinanceFuturesAccountRequestFactory factory = new BinanceFuturesAccountRequestFactory(coinMBinance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> factory.fundingRates(new BinanceFuturesFundingRateQuery(null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol is required");
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

    @Test
    void rejects_invalid_position_risk_query_shapes() {
        BinanceFuturesAccountRequestFactory usdmFactory = new BinanceFuturesAccountRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceFuturesAccountRequestFactory coinmFactory = new BinanceFuturesAccountRequestFactory(coinMBinance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> usdmFactory.positionRisk(
                new BinanceFuturesPositionRiskQuery(null, "BTCUSD", null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only supported for COIN-M");
        assertThatThrownBy(() -> coinmFactory.positionRisk(
                new BinanceFuturesPositionRiskQuery("BTCUSD_PERP", null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only supported for USD-M");
        assertThatThrownBy(() -> coinmFactory.positionRisk(
                new BinanceFuturesPositionRiskQuery(null, "BTCUSD", "BTC"),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only one of pair or marginAsset");
    }

    @Test
    void rejects_invalid_force_order_query_shapes() {
        BinanceFuturesAccountRequestFactory factory = new BinanceFuturesAccountRequestFactory(binance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> factory.forceOrders(
                new BinanceFuturesForceOrderQuery("BTCUSDT", "EXPIRED", null, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("autoCloseType must be one of");
        assertThatThrownBy(() -> factory.forceOrders(
                new BinanceFuturesForceOrderQuery("BTCUSDT", "ADL", null, null, 101),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be at most 100");
        assertThatThrownBy(() -> factory.forceOrders(
                new BinanceFuturesForceOrderQuery("BTCUSDT", "ADL", 0L, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startTime must be positive");
    }

    @Test
    void rejects_invalid_income_and_funding_rate_query_shapes() {
        BinanceFuturesAccountRequestFactory factory = new BinanceFuturesAccountRequestFactory(binance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> factory.income(
                new BinanceFuturesIncomeQuery("BTCUSDT", "FUNDING_FEE", 2L, 1L, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startTime must be less than or equal to endTime");
        assertThatThrownBy(() -> factory.income(
                new BinanceFuturesIncomeQuery("BTCUSDT", "FUNDING_FEE", null, null, null, 1001),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be at most 1000");
        assertThatThrownBy(() -> factory.fundingRates(
                new BinanceFuturesFundingRateQuery("BTCUSDT", null, null, 1001)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be at most 1000");
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
                pathPrefix + "/allOrders",
                pathPrefix + "/userTrades",
                null,
                null,
                null,
                pathPrefix + "/batchOrders",
                pathPrefix + "/order",
                pathPrefix + "/batchOrders",
                pathPrefix + "/orderAmendment",
                pathPrefix + "/batchOrders",
                pathPrefix + "/allOpenOrders",
                pathPrefix + "/countdownCancelAll",
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
        String readPathPrefix = pathPrefix.startsWith("/fapi") ? "/fapi/v3" : pathPrefix;
        return new BinanceProperties.FuturesAccount(
                "ONE_WAY",
                List.of("ONE_WAY", "HEDGE"),
                pathPrefix + "/positionSide/dual",
                pathPrefix + "/marginType",
                pathPrefix + "/leverage",
                readPathPrefix + "/balance",
                readPathPrefix + "/account",
                readPathPrefix + "/positionRisk",
                pathPrefix + "/adlQuantile",
                pathPrefix + "/forceOrders",
                pathPrefix + "/income",
                pathPrefix + "/fundingRate",
                multiAssetsModePath,
                1,
                125,
                List.of("CROSSED", "ISOLATED"),
                false,
                false
        );
    }
}
