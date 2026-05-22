package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
                "NONE",
                null,
                "tb_smoke_1",
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
                null
        );

        BinanceSignedRequest request = factory.newOrder(command, "test-secret");

        assertThat(request.payload())
                .isEqualTo("symbol=BTCUSDT&side=BUY&type=LIMIT&timeInForce=GTC&positionSide=BOTH"
                        + "&newOrderRespType=RESULT&selfTradePreventionMode=NONE&newClientOrderId=tb_smoke_1"
                        + "&quantity=0.001&price=50000"
                        + "&timestamp=1499827319559&recvWindow=5000");
        assertThat(request.uri().toString()).startsWith("https://demo-fapi.binance.com/fapi/v1/order?");
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
                new BinanceProperties.FuturesAccount("ONE_WAY", false, false)
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
                List.of("BUY", "SELL"),
                List.of("LIMIT", "MARKET", "STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET", "TRAILING_STOP_MARKET"),
                List.of("GTC", "IOC", "FOK", "GTX", "GTD"),
                List.of("ACK", "RESULT"),
                List.of("NONE", "EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH"),
                List.of("BOTH", "LONG", "SHORT"),
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
