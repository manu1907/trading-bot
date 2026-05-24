package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceOrderRequestFactoryTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_499_827_319_559L),
            ZoneOffset.UTC
    );

    @Test
    void builds_validated_new_order_request_from_command() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderCommand command = new BinanceOrderCommand(
                "BTCUSDT",
                "BUY",
                "LIMIT",
                "GTC",
                "BOTH",
                "RESULT",
                "EXPIRE_MAKER",
                null,
                null,
                null,
                null,
                null,
                null,
                "tb_smoke_1",
                null,
                new BigDecimal("0.001000"),
                null,
                new BigDecimal("50000.00"),
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
                null
        );

        BinanceSignedRequest request = factory.newOrder(command, "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&side=BUY&type=LIMIT&timeInForce=GTC&positionSide=BOTH"
                        + "&newOrderRespType=RESULT&selfTradePreventionMode=EXPIRE_MAKER&newClientOrderId=tb_smoke_1"
                        + "&quantity=0.001&price=50000"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/order?");
    }

    @Test
    void builds_gtd_price_match_order_request() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderCommand command = new BinanceOrderCommand(
                "BTCUSDT",
                "BUY",
                "LIMIT",
                "GTD",
                "BOTH",
                "RESULT",
                "EXPIRE_MAKER",
                null,
                "QUEUE",
                null,
                null,
                null,
                null,
                "tb_gtd_1",
                1_771_111_111_000L,
                new BigDecimal("0.001000"),
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
                null
        );

        BinanceSignedRequest request = factory.newOrder(command, "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&side=BUY&type=LIMIT&timeInForce=GTD&positionSide=BOTH"
                        + "&newOrderRespType=RESULT&selfTradePreventionMode=EXPIRE_MAKER&priceMatch=QUEUE"
                        + "&newClientOrderId=tb_gtd_1&goodTillDate=1771111111000&quantity=0.001"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/order?");
    }

    @Test
    void builds_futures_conditional_order_with_trigger_controls() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderCommand command = new BinanceOrderCommand(
                "BTCUSDT",
                "SELL",
                "STOP_MARKET",
                null,
                "BOTH",
                "RESULT",
                "EXPIRE_MAKER",
                null,
                null,
                "MARK_PRICE",
                null,
                null,
                null,
                "tb_stop_1",
                null,
                new BigDecimal("0.001000"),
                null,
                null,
                new BigDecimal("45000.00"),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null
        );

        BinanceSignedRequest request = factory.newOrder(command, "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&side=SELL&type=STOP_MARKET&positionSide=BOTH"
                        + "&newOrderRespType=RESULT&selfTradePreventionMode=EXPIRE_MAKER&workingType=MARK_PRICE"
                        + "&newClientOrderId=tb_stop_1&quantity=0.001&stopPrice=45000&priceProtect=false"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/order?");
    }

    @Test
    void builds_spot_pegged_limit_order_request() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);
        BinanceOrderCommand command = new BinanceOrderCommand(
                "BTCUSDT",
                "BUY",
                "LIMIT",
                "GTC",
                null,
                "FULL",
                "NONE",
                null,
                null,
                null,
                "PRIMARY_PEG",
                "PRICE_LEVEL",
                5,
                "tb_peg_1",
                null,
                new BigDecimal("0.001000"),
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
                null
        );

        BinanceSignedRequest request = factory.newOrder(command, "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&side=BUY&type=LIMIT&timeInForce=GTC"
                        + "&newOrderRespType=FULL&selfTradePreventionMode=NONE"
                        + "&pegPriceType=PRIMARY_PEG&pegOffsetType=PRICE_LEVEL&pegOffsetValue=5"
                        + "&newClientOrderId=tb_peg_1&quantity=0.001"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://api.binance.com/api/v3/order?");
    }

    @Test
    void builds_margin_order_with_side_effect_controls() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(marginBinance(), FIXED_CLOCK, 0);
        BinanceOrderCommand command = new BinanceOrderCommand(
                "BTCUSDT",
                "BUY",
                "MARKET",
                null,
                null,
                "FULL",
                "NONE",
                "AUTO_BORROW_REPAY",
                null,
                null,
                null,
                null,
                null,
                "tb_margin_1",
                null,
                new BigDecimal("0.001000"),
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
                false,
                true,
                null
        );

        BinanceSignedRequest request = factory.newOrder(command, "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&side=BUY&type=MARKET&newOrderRespType=FULL"
                        + "&selfTradePreventionMode=NONE&sideEffectType=AUTO_BORROW_REPAY"
                        + "&newClientOrderId=tb_margin_1&quantity=0.001"
                        + "&autoRepayAtCancel=false&isIsolated=true"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://api.binance.com/sapi/v1/margin/order?");
    }

    @Test
    void builds_cancel_order_request_by_original_client_order_id() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.cancelOrder("BTCUSDT", "tb_smoke_1", "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&origClientOrderId=tb_smoke_1&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/order?");
    }

    @Test
    void builds_query_order_request_by_original_client_order_id() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.queryOrder("BTCUSDT", "tb_smoke_1", "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&origClientOrderId=tb_smoke_1&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/order?");
    }

    @Test
    void builds_open_orders_request_with_optional_symbol() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.openOrders("BTCUSDT", "test-secret");

        assertThat(request.payload()).isEqualTo("symbol=BTCUSDT&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/openOrders?");
    }

    @Test
    void builds_futures_cancel_all_and_countdown_cancel_all_requests() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest cancelAll = factory.cancelAllOpenOrders("BTCUSDT", "test-secret");
        BinanceSignedRequest countdown = factory.countdownCancelAll("BTCUSDT", 120_000L, "test-secret");

        assertThat(cancelAll.payload()).isEqualTo("symbol=BTCUSDT&timestamp=1499827319559&recvWindow=5000");
        assertThat(cancelAll.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/allOpenOrders?");
        assertThat(countdown.payload())
                .isEqualTo("symbol=BTCUSDT&countdownTime=120000&timestamp=1499827319559&recvWindow=5000");
        assertThat(countdown.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/countdownCancelAll?");
    }

    @Test
    void rejects_invalid_futures_cancel_all_requests() {
        BinanceOrderRequestFactory futuresFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory spotFactory = new BinanceOrderRequestFactory(spotBinance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> futuresFactory.cancelAllOpenOrders(" ", "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol is required");
        assertThatThrownBy(() -> futuresFactory.countdownCancelAll("BTCUSDT", -1L, "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("countdownTime must be zero or positive");
        assertThatThrownBy(() -> spotFactory.cancelAllOpenOrders("BTCUSDT", "test-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cancelAllOpenOrdersPath is not configured");
    }

    @Test
    void builds_all_orders_history_request() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.allOrders(
                new BinanceOrderHistoryQuery("BTCUSDT", null, 12_345L, 1_700_000_000_000L, 1_700_001_000_000L, 100, null),
                "test-secret"
        );

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&orderId=12345&startTime=1700000000000&endTime=1700001000000"
                        + "&limit=100&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/allOrders?");
    }

    @Test
    void builds_margin_account_trade_history_request_with_isolated_flag() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(marginBinance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.accountTrades(
                new BinanceTradeHistoryQuery("BTCUSDT", null, null, null, null, 98L, 500, true),
                "test-secret"
        );

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&fromId=98&limit=500&isIsolated=TRUE"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://api.binance.com/sapi/v1/margin/myTrades?");
    }

    @Test
    void builds_coin_m_history_request_by_pair() {
        BinanceOrderRequestFactory factory = new BinanceOrderRequestFactory(coinmBinance(), FIXED_CLOCK, 0);

        BinanceSignedRequest request = factory.allOrders(
                new BinanceOrderHistoryQuery(null, "BTCUSD", null, null, null, 50, null),
                "test-secret"
        );

        assertThat(request.payload()).isEqualTo("pair=BTCUSD&limit=50&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-dapi.binance.com/dapi/v1/allOrders?");
    }

    @Test
    void rejects_invalid_history_query_shapes() {
        BinanceOrderRequestFactory usdmFactory = new BinanceOrderRequestFactory(binance(), FIXED_CLOCK, 0);
        BinanceOrderRequestFactory coinmFactory = new BinanceOrderRequestFactory(coinmBinance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> usdmFactory.allOrders(
                new BinanceOrderHistoryQuery(null, "BTCUSD", null, null, null, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol is required");
        assertThatThrownBy(() -> coinmFactory.accountTrades(
                new BinanceTradeHistoryQuery(null, "BTCUSD", 123L, null, null, null, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderId requires symbol");
        assertThatThrownBy(() -> coinmFactory.accountTrades(
                new BinanceTradeHistoryQuery(null, "BTCUSD", null, null, null, 123L, null, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromId cannot be sent with pair");
        assertThatThrownBy(() -> usdmFactory.allOrders(
                new BinanceOrderHistoryQuery("BTCUSDT", null, null, null, null, 0, null),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be positive");
        assertThatThrownBy(() -> usdmFactory.accountTrades(
                new BinanceTradeHistoryQuery("BTCUSDT", null, null, null, null, null, 100, true),
                "test-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isIsolated is only supported");
    }

    private BinanceProperties binance() {
        return new BinanceProperties(
                "FUTURES_USD_M",
                new BinanceProperties.Credentials(
                        "binance_demo_main",
                        "api-key",
                        "api-secret",
                        "HMAC_SHA256",
                        List.of("USER_STREAM", "USER_DATA", "TRADE")
                ),
                rest(),
                websocket(),
                trading(),
                userData(),
                new BinanceProperties.FuturesAccount(
                        "ONE_WAY",
                        List.of("ONE_WAY", "HEDGE"),
                        "/fapi/v1/positionSide/dual",
                        "/fapi/v1/marginType",
                        "/fapi/v1/leverage",
                        "/fapi/v3/balance",
                        "/fapi/v3/account",
                        "/fapi/v3/positionRisk",
                        "/fapi/v1/adlQuantile",
                        "/fapi/v1/forceOrders",
                        "/fapi/v1/income",
                        "/fapi/v1/fundingRate",
                        "/fapi/v1/multiAssetsMargin",
                        1,
                        125,
                        List.of("CROSSED", "ISOLATED"),
                        false,
                        false
                )
        );
    }

    private BinanceProperties spotBinance() {
        return new BinanceProperties(
                "SPOT",
                new BinanceProperties.Credentials(
                        "binance_real_main",
                        "api-key",
                        "api-secret",
                        "HMAC_SHA256",
                        List.of("USER_DATA", "TRADE")
                ),
                spotRest(),
                websocket(),
                spotTrading(),
                userData(),
                null
        );
    }

    private BinanceProperties marginBinance() {
        return new BinanceProperties(
                "MARGIN_ISOLATED",
                new BinanceProperties.Credentials(
                        "binance_real_main",
                        "api-key",
                        "api-secret",
                        "HMAC_SHA256",
                        List.of("USER_DATA", "MARGIN", "TRADE")
                ),
                spotRest(),
                websocket(),
                marginTrading(),
                userData(),
                null
        );
    }

    private BinanceProperties coinmBinance() {
        return new BinanceProperties(
                "FUTURES_COIN_M",
                new BinanceProperties.Credentials(
                        "binance_demo_main",
                        "api-key",
                        "api-secret",
                        "HMAC_SHA256",
                        List.of("USER_STREAM", "USER_DATA", "TRADE")
                ),
                coinmRest(),
                websocket(),
                coinmTrading(),
                userData(),
                new BinanceProperties.FuturesAccount(
                        "ONE_WAY",
                        List.of("ONE_WAY", "HEDGE"),
                        "/dapi/v1/positionSide/dual",
                        "/dapi/v1/marginType",
                        "/dapi/v1/leverage",
                        "/dapi/v1/balance",
                        "/dapi/v1/account",
                        "/dapi/v1/positionRisk",
                        "/dapi/v1/adlQuantile",
                        "/dapi/v1/forceOrders",
                        "/dapi/v1/income",
                        "/dapi/v1/fundingRate",
                        null,
                        1,
                        125,
                        List.of("CROSSED", "ISOLATED"),
                        false,
                        false
                )
        );
    }

    private BinanceProperties.Rest rest() {
        return new BinanceProperties.Rest(
                "https://demo-fapi.binance.com",
                "/fapi/v1/exchangeInfo",
                "/fapi/v1/time",
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

    private BinanceProperties.Rest spotRest() {
        return new BinanceProperties.Rest(
                "https://api.binance.com",
                "/api/v3/exchangeInfo",
                "/api/v3/time",
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
                "FULL",
                new BinanceProperties.UnknownExecutionStatus(
                        List.of("Unknown error, please check your request or try again later."),
                        List.of(503)
                )
        );
    }

    private BinanceProperties.Rest coinmRest() {
        return new BinanceProperties.Rest(
                "https://demo-dapi.binance.com",
                "/dapi/v1/exchangeInfo",
                "/dapi/v1/time",
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

    private BinanceProperties.Trading trading() {
        return new BinanceProperties.Trading(
                "/fapi/v1/order",
                null,
                "/fapi/v1/order",
                "/fapi/v1/order",
                "/fapi/v1/openOrders",
                "/fapi/v1/allOrders",
                "/fapi/v1/userTrades",
                "/fapi/v1/allOpenOrders",
                "/fapi/v1/countdownCancelAll",
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

    private BinanceProperties.Trading spotTrading() {
        return new BinanceProperties.Trading(
                "/api/v3/order",
                "/api/v3/order/test",
                "/api/v3/order",
                "/api/v3/order",
                "/api/v3/openOrders",
                "/api/v3/allOrders",
                "/api/v3/myTrades",
                null,
                null,
                List.of("BUY", "SELL"),
                List.of("LIMIT", "MARKET", "STOP_LOSS", "STOP_LOSS_LIMIT", "TAKE_PROFIT", "TAKE_PROFIT_LIMIT", "LIMIT_MAKER"),
                List.of("GTC", "IOC", "FOK"),
                List.of("ACK", "RESULT", "FULL"),
                List.of("NONE", "EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH"),
                List.of("NONE"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("LIMIT", "LIMIT_MAKER", "STOP_LOSS_LIMIT", "TAKE_PROFIT_LIMIT"),
                List.of("PRIMARY_PEG", "MARKET_PEG"),
                List.of("PRICE_LEVEL"),
                List.of(),
                List.of(),
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

    private BinanceProperties.Trading marginTrading() {
        return new BinanceProperties.Trading(
                "/sapi/v1/margin/order",
                null,
                "/sapi/v1/margin/order",
                "/sapi/v1/margin/order",
                "/sapi/v1/margin/openOrders",
                "/sapi/v1/margin/allOrders",
                "/sapi/v1/margin/myTrades",
                null,
                null,
                List.of("BUY", "SELL"),
                List.of("LIMIT", "MARKET", "STOP_LOSS", "STOP_LOSS_LIMIT", "TAKE_PROFIT", "TAKE_PROFIT_LIMIT", "LIMIT_MAKER"),
                List.of("GTC", "IOC", "FOK"),
                List.of("ACK", "RESULT", "FULL"),
                List.of("NONE", "EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH"),
                List.of("NONE"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("NO_SIDE_EFFECT", "MARGIN_BUY", "AUTO_REPAY", "AUTO_BORROW_REPAY"),
                List.of("MARGIN_BUY", "AUTO_BORROW_REPAY"),
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
                true,
                false
        );
    }

    private BinanceProperties.Trading coinmTrading() {
        return new BinanceProperties.Trading(
                "/dapi/v1/order",
                null,
                "/dapi/v1/order",
                "/dapi/v1/order",
                "/dapi/v1/openOrders",
                "/dapi/v1/allOrders",
                "/dapi/v1/userTrades",
                "/dapi/v1/allOpenOrders",
                "/dapi/v1/countdownCancelAll",
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

    private BinanceProperties.UserDataStream userData() {
        return new BinanceProperties.UserDataStream(
                "listen_key",
                "/fapi/v1/listenKey",
                "/fapi/v1/listenKey",
                "/fapi/v1/listenKey",
                60,
                30,
                1
        );
    }
}
