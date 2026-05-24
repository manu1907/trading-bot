package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceOrderClientTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_499_827_319_559L),
            ZoneOffset.UTC
    );

    @Test
    void places_order_and_parses_common_order_fields() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                {
                  "symbol": "BTCUSDT",
                  "orderId": 12345,
                  "clientOrderId": "tb_1",
                  "status": "NEW",
                  "side": "BUY",
                  "type": "LIMIT",
                  "positionSide": "LONG",
                  "price": "50000.00",
                  "origQty": "0.001",
                  "executedQty": "0",
                  "avgPrice": "0.00",
                  "cumQuote": "0",
                  "updateTime": 1668481559918
                }
                """));
        BinanceOrderClient client = client(transport);

        BinanceOrderResult result = client.placeOrder(limitOrder());

        assertThat(result.orderId()).isEqualTo(12345L);
        assertThat(result.clientOrderId()).isEqualTo("tb_1");
        assertThat(result.price()).isEqualByComparingTo("50000.00");
        assertThat(transport.calls()).hasSize(1);
        assertThat(transport.calls().getFirst().method()).isEqualTo("POST");
        assertThat(transport.calls().getFirst().uri()).contains("/fapi/v1/order?");
    }

    @Test
    void places_batch_orders_and_preserves_per_order_errors() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                [
                  {
                    "symbol": "BTCUSDT",
                    "orderId": 12345,
                    "clientOrderId": "batch_1",
                    "status": "NEW",
                    "side": "BUY",
                    "type": "LIMIT",
                    "positionSide": "LONG",
                    "price": "50000.00",
                    "origQty": "0.001",
                    "executedQty": "0",
                    "avgPrice": "0.00",
                    "cumQuote": "0",
                    "updateTime": 1668481559918
                  },
                  {
                    "code": -2022,
                    "msg": "ReduceOnly Order is rejected."
                  }
                ]
                """));
        BinanceOrderClient client = client(transport);

        List<BinanceBatchOrderResult> results = client.placeBatchOrders(List.of(limitOrder(), limitOrder()));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).order().clientOrderId()).isEqualTo("batch_1");
        assertThat(results.get(0).code()).isNull();
        assertThat(results.get(1).order()).isNull();
        assertThat(results.get(1).code()).isEqualTo(-2022);
        assertThat(results.get(1).message()).contains("ReduceOnly");
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.uri()).contains("/fapi/v1/batchOrders?batchOrders=");
        });
    }

    @Test
    void modifies_order_and_parses_response() {
        FakeTransport transport = new FakeTransport(orderResponse("MODIFY", "NEW"));
        BinanceOrderClient client = client(transport);

        BinanceOrderResult result = client.modifyOrder(new BinanceModifyOrderCommand(
                "BTCUSDT",
                12345L,
                null,
                "BUY",
                new BigDecimal("0.001"),
                new BigDecimal("50000"),
                null
        ));

        assertThat(result.clientOrderId()).isEqualTo("MODIFY");
        assertThat(result.status()).isEqualTo("NEW");
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("PUT");
            assertThat(call.uri()).contains("/fapi/v1/order?symbol=BTCUSDT&orderId=12345");
        });
    }

    @Test
    void queries_cancels_and_lists_orders() {
        FakeTransport transport = new FakeTransport(
                orderResponse("QUERY", "FILLED"),
                orderResponse("CANCEL", "CANCELED"),
                new BinanceHttpResponse(200, "[" + orderResponseBody("OPEN", "NEW") + "]"),
                new BinanceHttpResponse(200, """
                        {
                          "code": 200,
                          "msg": "The operation of cancel all open order is done."
                        }
                        """),
                new BinanceHttpResponse(200, "[" + orderResponseBody("BATCH_CANCEL", "CANCELED") + "]"),
                new BinanceHttpResponse(200, """
                        {
                          "symbol": "BTCUSDT",
                          "countdownTime": "120000"
                        }
                        """)
        );
        BinanceOrderClient client = client(transport);

        BinanceOrderResult queried = client.queryOrder("BTCUSDT", "tb_query");
        BinanceOrderResult cancelled = client.cancelOrder("BTCUSDT", "tb_cancel");
        List<BinanceOrderResult> openOrders = client.openOrders("BTCUSDT");
        BinanceOrderAck cancelAll = client.cancelAllOpenOrders("BTCUSDT");
        List<BinanceOrderResult> cancelMultiple = client.cancelMultipleOrders(
                new BinanceCancelMultipleOrdersQuery("BTCUSDT", List.of(12345L), List.of())
        );
        BinanceCountdownCancelAll countdown = client.countdownCancelAll("BTCUSDT", 120_000L);

        assertThat(queried.status()).isEqualTo("FILLED");
        assertThat(cancelled.status()).isEqualTo("CANCELED");
        assertThat(openOrders).singleElement().extracting(BinanceOrderResult::status).isEqualTo("NEW");
        assertThat(cancelAll.code()).isEqualTo(200);
        assertThat(cancelMultiple).singleElement().extracting(BinanceOrderResult::status).isEqualTo("CANCELED");
        assertThat(countdown.countdownTime()).isEqualTo(120_000L);
        assertThat(transport.calls()).extracting(FakeCall::method)
                .containsExactly("GET", "DELETE", "GET", "DELETE", "DELETE", "POST");
        assertThat(transport.calls()).extracting(FakeCall::uri)
                .anySatisfy(uri -> assertThat(uri).contains("/fapi/v1/allOpenOrders?symbol=BTCUSDT"))
                .anySatisfy(uri -> assertThat(uri).contains("/fapi/v1/batchOrders?symbol=BTCUSDT"))
                .anySatisfy(uri -> assertThat(uri).contains("orderIdList=%5B12345%5D"))
                .anySatisfy(uri -> assertThat(uri).contains("/fapi/v1/countdownCancelAll?symbol=BTCUSDT&countdownTime=120000"));
    }

    @Test
    void queries_order_and_trade_history_for_reconciliation() {
        FakeTransport transport = new FakeTransport(
                new BinanceHttpResponse(200, "[" + orderResponseBody("HISTORY", "FILLED") + "]"),
                new BinanceHttpResponse(200, """
                        [
                          {
                            "symbol": "BTCUSDT",
                            "id": 698759,
                            "orderId": 25851813,
                            "price": "7819.01",
                            "qty": "0.002",
                            "quoteQty": "15.63802",
                            "realizedPnl": "-0.91539999",
                            "commission": "0.07819010",
                            "commissionAsset": "USDT",
                            "side": "SELL",
                            "positionSide": "SHORT",
                            "buyer": false,
                            "maker": false,
                            "time": 1569514978020
                          }
                        ]
                        """)
        );
        BinanceOrderClient client = client(transport);

        List<BinanceOrderResult> orders = client.allOrders(
                new BinanceOrderHistoryQuery("BTCUSDT", null, null, 1L, 2L, 100, null)
        );
        List<BinanceAccountTrade> trades = client.accountTrades(
                new BinanceTradeHistoryQuery("BTCUSDT", null, 25851813L, null, null, null, 500, null)
        );

        assertThat(orders).singleElement().extracting(BinanceOrderResult::status).isEqualTo("FILLED");
        assertThat(trades).singleElement().satisfies(trade -> {
            assertThat(trade.orderId()).isEqualTo(25851813L);
            assertThat(trade.realizedPnl()).isEqualByComparingTo("-0.91539999");
            assertThat(trade.buyer()).isFalse();
        });
        assertThat(transport.calls()).extracting(FakeCall::method).containsExactly("GET", "GET");
        assertThat(transport.calls()).extracting(FakeCall::uri).allSatisfy(uri -> assertThat(uri).doesNotContain("test-secret"));
    }

    @Test
    void throws_sanitized_binance_api_exception_for_exchange_error() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(400, """
                {"code": -4061, "msg": "Order's position side does not match user's setting."}
                """));
        BinanceOrderClient client = client(transport);

        assertThatThrownBy(() -> client.placeOrder(limitOrder()))
                .isInstanceOf(BinanceApiException.class)
                .hasMessageContaining("httpStatusCode=400")
                .hasMessageContaining("exchangeCode=-4061")
                .hasMessageContaining("position side");
    }

    @Test
    void captures_rate_limit_headers_from_order_responses() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, orderResponseBody("tb_1", "NEW"), Map.of(
                "X-MBX-USED-WEIGHT-1M", List.of("41"),
                "X-MBX-ORDER-COUNT-10S", List.of("2")
        )));
        BinanceOrderClient client = client(transport);

        client.placeOrder(limitOrder());

        assertThat(client.currentRateLimitUsage()).hasValueSatisfying(usage -> {
            assertThat(usage.usedWeights()).containsEntry("X-MBX-USED-WEIGHT-1M", 41L);
            assertThat(usage.orderCounts()).containsEntry("X-MBX-ORDER-COUNT-10S", 2L);
        });
    }

    private BinanceOrderClient client(FakeTransport transport) {
        return new BinanceOrderClient(
                binance(),
                "api-key",
                "test-secret",
                FIXED_CLOCK,
                0,
                transport,
                JsonMapperFactory.create()
        );
    }

    private BinanceOrderCommand limitOrder() {
        return new BinanceOrderCommand(
                "BTCUSDT",
                "BUY",
                "LIMIT",
                "GTC",
                "LONG",
                "RESULT",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "tb_1",
                null,
                new BigDecimal("0.001"),
                null,
                new BigDecimal("50000"),
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

    private BinanceHttpResponse orderResponse(String clientOrderId, String status) {
        return new BinanceHttpResponse(200, orderResponseBody(clientOrderId, status));
    }

    private String orderResponseBody(String clientOrderId, String status) {
        return "{"
                + "\"symbol\":\"BTCUSDT\","
                + "\"orderId\":12345,"
                + "\"clientOrderId\":\"" + clientOrderId + "\","
                + "\"status\":\"" + status + "\","
                + "\"side\":\"BUY\","
                + "\"type\":\"LIMIT\","
                + "\"positionSide\":\"LONG\","
                + "\"price\":\"50000.00\","
                + "\"origQty\":\"0.001\","
                + "\"executedQty\":\"0\","
                + "\"avgPrice\":\"0.00\","
                + "\"cumQuote\":\"0\","
                + "\"updateTime\":1668481559918"
                + "}";
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
                futuresAccount()
        );
    }

    private BinanceProperties.FuturesAccount futuresAccount() {
        return new BinanceProperties.FuturesAccount(
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
                "/fapi/v1/allOrders",
                "/fapi/v1/userTrades",
                "/fapi/v1/batchOrders",
                "/fapi/v1/order",
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

    private record FakeCall(String method, String uri) {
    }

    private static final class FakeTransport implements BinanceHttpTransport {
        private final List<BinanceHttpResponse> responses;
        private final List<FakeCall> calls = new ArrayList<>();

        FakeTransport(BinanceHttpResponse... responses) {
            this.responses = new ArrayList<>(List.of(responses));
        }

        @Override
        public BinanceHttpResponse sendPublic(URI uri, String method) {
            throw new UnsupportedOperationException("public requests are not used by this test");
        }

        @Override
        public BinanceHttpResponse send(BinanceSignedRequest request,
                                        String method,
                                        String apiKey,
                                        String apiKeyHeader) {
            calls.add(new FakeCall(method, request.uri().toString()));
            return responses.removeFirst();
        }

        List<FakeCall> calls() {
            return List.copyOf(calls);
        }
    }
}
