package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceWebSocketApiRequestFactoryTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_645_423_376_532L),
            ZoneOffset.UTC
    );

    private final ObjectMapper jsonMapper = JsonMapperFactory.create();

    @Test
    void builds_signed_spot_order_place_request() {
        BinanceWebSocketApiRequestFactory factory = new BinanceWebSocketApiRequestFactory(spotBinance(), FIXED_CLOCK, 0);

        BinanceWebSocketApiRequest request = factory.placeOrder("request-1", spotLimitOrder(), "api-key", "secret-key");

        assertThat(request.id()).isEqualTo("request-1");
        assertThat(request.method()).isEqualTo("order.place");
        assertThat(request.signaturePayload())
                .isEqualTo("apiKey=api-key&price=52000&quantity=0.01&recvWindow=100&side=SELL"
                        + "&symbol=BTCUSDT&timeInForce=GTC&timestamp=1645423376532&type=LIMIT");
        assertThat(request.signature())
                .isEqualTo(BinanceRequestSigner.sign(request.signaturePayload(), "secret-key", "HMAC_SHA256"));

        JsonNode root = jsonMapper.readTree(request.payload());
        JsonNode params = root.required("params");
        assertThat(root.required("id").asString()).isEqualTo("request-1");
        assertThat(root.required("method").asString()).isEqualTo("order.place");
        assertThat(params.required("symbol").asString()).isEqualTo("BTCUSDT");
        assertThat(params.required("price").asString()).isEqualTo("52000");
        assertThat(params.required("quantity").asString()).isEqualTo("0.01");
        assertThat(params.required("apiKey").asString()).isEqualTo("api-key");
        assertThat(params.required("recvWindow").asInt()).isEqualTo(100);
        assertThat(params.required("timestamp").asLong()).isEqualTo(1_645_423_376_532L);
        assertThat(params.required("signature").asString()).isEqualTo(request.signature());
        assertThat(params.hasNonNull("positionSide")).isFalse();
    }

    @Test
    void rejects_non_spot_websocket_api_order_placement() {
        BinanceWebSocketApiRequestFactory factory = new BinanceWebSocketApiRequestFactory(futuresBinance(), FIXED_CLOCK, 0);

        assertThatThrownBy(() -> factory.placeOrder("request-1", spotLimitOrder(), "api-key", "secret-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only configured for Spot");
    }

    private BinanceOrderCommand spotLimitOrder() {
        return new BinanceOrderCommand(
                "BTCUSDT",
                "SELL",
                "LIMIT",
                "GTC",
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
                new BigDecimal("0.01000000"),
                null,
                new BigDecimal("52000.00"),
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
    }

    private BinanceProperties spotBinance() {
        return new BinanceProperties(
                "SPOT",
                credentials(),
                rest(),
                websocket("wss://ws-api.binance.com:443", "/ws-api/v3"),
                spotTrading(),
                userData(),
                null,
                null
        );
    }

    private BinanceProperties futuresBinance() {
        return new BinanceProperties(
                "FUTURES_USD_M",
                credentials(),
                rest(),
                websocket(null, null),
                futuresTrading(),
                userData(),
                null,
                null
        );
    }

    private BinanceProperties.Credentials credentials() {
        return new BinanceProperties.Credentials(
                "binance_real_main",
                "api-key",
                "api-secret",
                "HMAC_SHA256",
                List.of("USER_DATA", "TRADE")
        );
    }

    private BinanceProperties.Rest rest() {
        return new BinanceProperties.Rest(
                "https://api.binance.com",
                "/api/v3/exchangeInfo",
                "/api/v3/time",
                "X-MBX-APIKEY",
                "HMAC_SHA256",
                "MILLISECONDS",
                100,
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

    private BinanceProperties.Websocket websocket(String apiBaseUrl, String apiPath) {
        return new BinanceProperties.Websocket(
                "wss://stream.binance.com:9443",
                null,
                null,
                null,
                "/ws",
                "/stream",
                24,
                10,
                20,
                null,
                60,
                null,
                5,
                1024,
                300,
                "MILLISECONDS",
                apiBaseUrl,
                apiPath
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
                "/api/v3/account/commission",
                "/api/v3/myPreventedMatches",
                "/api/v3/order/amend/keepPriority",
                "/api/v3/order/cancelReplace",
                "/api/v3/sor/order",
                "/api/v3/sor/order/test",
                "/api/v3/orderList/oco",
                "/api/v3/orderList/oto",
                "/api/v3/orderList/otoco",
                "/api/v3/orderList/opo",
                "/api/v3/orderList/opoco",
                null,
                null,
                null,
                null,
                null,
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

    private BinanceProperties.Trading futuresTrading() {
        return new BinanceProperties.Trading(
                "/fapi/v1/order",
                null,
                "/fapi/v1/order",
                "/fapi/v1/order",
                "/fapi/v1/openOrders",
                "/fapi/v1/allOrders",
                "/fapi/v1/userTrades",
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
                "/fapi/v1/batchOrders",
                "/fapi/v1/order",
                "/fapi/v1/batchOrders",
                "/fapi/v1/orderAmendment",
                "/fapi/v1/batchOrders",
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

    private BinanceProperties.UserDataStream userData() {
        return new BinanceProperties.UserDataStream("websocket_api", null, null, null, null, null, null);
    }
}
