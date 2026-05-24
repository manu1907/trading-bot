package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceWebSocketApiOrderClientTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_645_423_376_532L),
            ZoneOffset.UTC
    );

    @Test
    void places_spot_order_over_websocket_api_and_parses_result() {
        FakeWebSocketTransport transport = new FakeWebSocketTransport("""
                {
                  "id": "request-1",
                  "status": 200,
                  "result": {
                    "symbol": "BTCUSDT",
                    "orderId": 12569099453,
                    "orderListId": -1,
                    "clientOrderId": "ws-order-1",
                    "transactTime": 1660801715639,
                    "price": "52000.00000000",
                    "origQty": "0.01000000",
                    "executedQty": "0.00000000",
                    "origQuoteOrderQty": "0.000000",
                    "cummulativeQuoteQty": "0.00000000",
                    "status": "NEW",
                    "timeInForce": "GTC",
                    "type": "LIMIT",
                    "side": "SELL",
                    "workingTime": 1660801715639,
                    "selfTradePreventionMode": "NONE"
                  },
                  "rateLimits": []
                }
                """);
        BinanceWebSocketApiOrderClient client = client(transport);

        BinanceOrderResult result = client.placeOrder("request-1", spotLimitOrder());

        assertThat(result.orderId()).isEqualTo(12_569_099_453L);
        assertThat(result.clientOrderId()).isEqualTo("ws-order-1");
        assertThat(result.status()).isEqualTo("NEW");
        assertThat(result.cumulativeQuote()).isEqualByComparingTo("0.00000000");
        assertThat(result.updateTime()).isEqualTo(1_660_801_715_639L);
        assertThat(transport.connectedPlan.uri().toString()).isEqualTo("wss://ws-api.binance.com:443/ws-api/v3");
        assertThat(transport.sentMessages).singleElement().satisfies(message -> {
            assertThat(message).contains("\"method\":\"order.place\"");
            assertThat(message).contains("\"newClientOrderId\":\"ws-order-1\"");
            assertThat(message).contains("\"signature\":");
            assertThat(message).doesNotContain("secret-key");
        });
        assertThat(transport.closeCount).hasValue(1);
    }

    @Test
    void surfaces_websocket_api_error_response() {
        FakeWebSocketTransport transport = new FakeWebSocketTransport("""
                {
                  "id": "request-1",
                  "status": 400,
                  "error": {
                    "code": -2010,
                    "msg": "Order would immediately match and take."
                  },
                  "rateLimits": []
                }
                """);
        BinanceWebSocketApiOrderClient client = client(transport);

        assertThatThrownBy(() -> client.placeOrder("request-1", spotLimitOrder()))
                .isInstanceOf(BinanceApiException.class)
                .hasMessageContaining("httpStatusCode=400")
                .hasMessageContaining("exchangeCode=-2010")
                .hasMessageContaining("immediately match");
    }

    private BinanceWebSocketApiOrderClient client(FakeWebSocketTransport transport) {
        return new BinanceWebSocketApiOrderClient(
                spotBinance(),
                "api-key",
                "secret-key",
                FIXED_CLOCK,
                0,
                transport,
                JsonMapperFactory.create(),
                Duration.ofSeconds(1)
        );
    }

    private BinanceOrderCommand spotLimitOrder() {
        return new BinanceOrderCommand(
                "BTCUSDT",
                "SELL",
                "LIMIT",
                "GTC",
                null,
                "RESULT",
                "NONE",
                null,
                null,
                null,
                null,
                null,
                null,
                "ws-order-1",
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
                new BinanceProperties.Credentials(
                        "binance_real_main",
                        "api-key",
                        "api-secret",
                        "HMAC_SHA256",
                        List.of("USER_DATA", "TRADE")
                ),
                rest(),
                websocket(),
                spotTrading(),
                userData(),
                null,
                null
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

    private BinanceProperties.Websocket websocket() {
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
                "wss://ws-api.binance.com:443",
                "/ws-api/v3"
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

    private BinanceProperties.UserDataStream userData() {
        return new BinanceProperties.UserDataStream("websocket_api", null, null, null, null, null, null);
    }

    private static final class FakeWebSocketTransport implements BinanceWebSocketTransport {

        private final String response;
        private final List<String> sentMessages = new ArrayList<>();
        private final AtomicInteger closeCount = new AtomicInteger();
        private BinanceWebSocketConnectionPlan connectedPlan;
        private BinanceWebSocketListener listener;

        private FakeWebSocketTransport(String response) {
            this.response = response;
        }

        @Override
        public BinanceWebSocketConnection connect(
                BinanceWebSocketConnectionPlan plan,
                BinanceWebSocketListener listener
        ) {
            this.connectedPlan = plan;
            this.listener = listener;
            listener.onOpen(plan);
            return new BinanceWebSocketConnection(
                    plan,
                    Instant.parse("2026-05-22T20:00:01Z"),
                    closeCount::incrementAndGet,
                    this::send
            );
        }

        private void send(String text) {
            sentMessages.add(text);
            listener.onText(response);
        }
    }
}
