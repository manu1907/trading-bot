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
    void modifies_multiple_orders_and_preserves_per_order_errors() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                [
                  {
                    "symbol": "BTCUSDT",
                    "orderId": 12345,
                    "clientOrderId": "modify_1",
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

        List<BinanceBatchOrderResult> results = client.modifyMultipleOrders(List.of(modifyOrder(), modifyOrder()));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).order().clientOrderId()).isEqualTo("modify_1");
        assertThat(results.get(1).order()).isNull();
        assertThat(results.get(1).code()).isEqualTo(-2022);
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("PUT");
            assertThat(call.uri()).contains("/fapi/v1/batchOrders?batchOrders=");
        });
    }

    @Test
    void queries_modify_order_history() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                [
                  {
                    "amendmentId": 5363,
                    "symbol": "BTCUSDT",
                    "pair": "BTCUSDT",
                    "orderId": 20072994037,
                    "clientOrderId": "client_1",
                    "time": 1629184560899,
                    "amendment": {
                      "price": {
                        "before": "30004",
                        "after": "30003.2"
                      },
                      "origQty": {
                        "before": "1",
                        "after": "2"
                      },
                      "count": 3
                    }
                  }
                ]
                """));
        BinanceOrderClient client = client(transport);

        List<BinanceOrderAmendment> amendments = client.modifyOrderHistory(
                new BinanceModifyOrderHistoryQuery("BTCUSDT", 20072994037L, null, 1L, 2L, 50)
        );

        assertThat(amendments).singleElement().satisfies(amendment -> {
            assertThat(amendment.amendmentId()).isEqualTo(5363L);
            assertThat(amendment.orderId()).isEqualTo(20072994037L);
            assertThat(amendment.priceBefore()).isEqualByComparingTo("30004");
            assertThat(amendment.priceAfter()).isEqualByComparingTo("30003.2");
            assertThat(amendment.originalQuantityBefore()).isEqualByComparingTo("1");
            assertThat(amendment.originalQuantityAfter()).isEqualByComparingTo("2");
            assertThat(amendment.count()).isEqualTo(3);
        });
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.uri()).contains("/fapi/v1/orderAmendment?symbol=BTCUSDT&orderId=20072994037");
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
    void queries_spot_commission_rates() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                {
                  "symbol": "BTCUSDT",
                  "standardCommission": {
                    "maker": "0.00000010",
                    "taker": "0.00000020",
                    "buyer": "0.00000030",
                    "seller": "0.00000040"
                  },
                  "specialCommission": {
                    "maker": "0.01000000",
                    "taker": "0.02000000",
                    "buyer": "0.03000000",
                    "seller": "0.04000000"
                  },
                  "taxCommission": {
                    "maker": "0.00000112",
                    "taker": "0.00000114",
                    "buyer": "0.00000118",
                    "seller": "0.00000116"
                  },
                  "discount": {
                    "enabledForAccount": true,
                    "enabledForSymbol": true,
                    "discountAsset": "BNB",
                    "discount": "0.75000000"
                  }
                }
                """));
        BinanceOrderClient client = spotClient(transport);

        BinanceSpotCommissionRates rates = client.commissionRates("BTCUSDT");

        assertThat(rates.symbol()).isEqualTo("BTCUSDT");
        assertThat(rates.standardCommission().maker()).isEqualByComparingTo("0.00000010");
        assertThat(rates.specialCommission().seller()).isEqualByComparingTo("0.04000000");
        assertThat(rates.taxCommission().buyer()).isEqualByComparingTo("0.00000118");
        assertThat(rates.discount().enabledForAccount()).isTrue();
        assertThat(rates.discount().enabledForSymbol()).isTrue();
        assertThat(rates.discount().discountAsset()).isEqualTo("BNB");
        assertThat(rates.discount().discount()).isEqualByComparingTo("0.75000000");
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.uri()).contains("/api/v3/account/commission?symbol=BTCUSDT");
            assertThat(call.uri()).doesNotContain("test-secret");
        });
    }

    @Test
    void queries_spot_prevented_matches() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                [
                  {
                    "symbol": "BTCUSDT",
                    "preventedMatchId": 1,
                    "takerOrderId": 5,
                    "makerSymbol": "BTCUSDT",
                    "makerOrderId": 3,
                    "tradeGroupId": 1,
                    "selfTradePreventionMode": "EXPIRE_MAKER",
                    "price": "1.100000",
                    "makerPreventedQuantity": "1.300000",
                    "transactTime": 1669101687094
                  }
                ]
                """));
        BinanceOrderClient client = spotClient(transport);

        List<BinancePreventedMatch> matches = client.preventedMatches(
                new BinancePreventedMatchesQuery("BTCUSDT", null, 5L, 1L, 100)
        );

        assertThat(matches).singleElement().satisfies(match -> {
            assertThat(match.symbol()).isEqualTo("BTCUSDT");
            assertThat(match.preventedMatchId()).isEqualTo(1L);
            assertThat(match.takerOrderId()).isEqualTo(5L);
            assertThat(match.makerOrderId()).isEqualTo(3L);
            assertThat(match.tradeGroupId()).isEqualTo(1L);
            assertThat(match.selfTradePreventionMode()).isEqualTo("EXPIRE_MAKER");
            assertThat(match.price()).isEqualByComparingTo("1.100000");
            assertThat(match.makerPreventedQuantity()).isEqualByComparingTo("1.300000");
            assertThat(match.transactTime()).isEqualTo(1669101687094L);
        });
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.uri()).contains("/api/v3/myPreventedMatches?symbol=BTCUSDT&orderId=5");
            assertThat(call.uri()).contains("fromPreventedMatchId=1&limit=100");
            assertThat(call.uri()).doesNotContain("test-secret");
        });
    }

    @Test
    void amends_spot_order_keep_priority() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                {
                  "transactTime": 1741926410255,
                  "executionId": 75,
                  "amendedOrder": {
                    "symbol": "BTCUSDT",
                    "orderId": 33,
                    "orderListId": -1,
                    "origClientOrderId": "original-33",
                    "clientOrderId": "amended-33",
                    "price": "6.00000000",
                    "qty": "5.00000000",
                    "executedQty": "0.00000000",
                    "preventedQty": "0.00000000",
                    "quoteOrderQty": "0.00000000",
                    "cumulativeQuoteQty": "0.00000000",
                    "status": "NEW",
                    "timeInForce": "GTC",
                    "type": "LIMIT",
                    "side": "SELL",
                    "workingTime": 1741926410242,
                    "selfTradePreventionMode": "NONE"
                  }
                }
                """));
        BinanceOrderClient client = spotClient(transport);

        BinanceAmendKeepPriorityResult result = client.amendKeepPriority(
                new BinanceAmendKeepPriorityCommand("BTCUSDT", 33L, null, "amended-33", new BigDecimal("5.00000000"))
        );

        assertThat(result.transactTime()).isEqualTo(1741926410255L);
        assertThat(result.executionId()).isEqualTo(75L);
        assertThat(result.amendedOrder()).satisfies(order -> {
            assertThat(order.symbol()).isEqualTo("BTCUSDT");
            assertThat(order.orderId()).isEqualTo(33L);
            assertThat(order.originalClientOrderId()).isEqualTo("original-33");
            assertThat(order.clientOrderId()).isEqualTo("amended-33");
            assertThat(order.quantity()).isEqualByComparingTo("5.00000000");
            assertThat(order.cumulativeQuoteQuantity()).isEqualByComparingTo("0.00000000");
            assertThat(order.status()).isEqualTo("NEW");
        });
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("PUT");
            assertThat(call.uri()).contains("/api/v3/order/amend/keepPriority?symbol=BTCUSDT&orderId=33");
            assertThat(call.uri()).contains("newClientOrderId=amended-33&newQty=5");
            assertThat(call.uri()).doesNotContain("test-secret");
        });
    }

    @Test
    void cancels_and_replaces_spot_order() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                {
                  "cancelResult": "SUCCESS",
                  "newOrderResult": "SUCCESS",
                  "cancelResponse": {
                    "symbol": "BTCUSDT",
                    "origClientOrderId": "old-1",
                    "orderId": 33,
                    "clientOrderId": "cancel-1",
                    "price": "50000.00000000",
                    "origQty": "0.00100000",
                    "executedQty": "0.00000000",
                    "cummulativeQuoteQty": "0.00000000",
                    "status": "CANCELED",
                    "timeInForce": "GTC",
                    "type": "LIMIT",
                    "side": "BUY"
                  },
                  "newOrderResponse": {
                    "symbol": "BTCUSDT",
                    "orderId": 34,
                    "clientOrderId": "replace-1",
                    "transactTime": 1660813156959,
                    "price": "49900.00000000",
                    "origQty": "0.00100000",
                    "executedQty": "0.00000000",
                    "cummulativeQuoteQty": "0.00000000",
                    "status": "NEW",
                    "timeInForce": "GTC",
                    "type": "LIMIT",
                    "side": "BUY"
                  }
                }
                """));
        BinanceOrderClient client = spotClient(transport);

        BinanceCancelReplaceResult result = client.cancelReplace(new BinanceCancelReplaceCommand(
                spotLimitOrder("replace-1"),
                "STOP_ON_FAILURE",
                "cancel-1",
                null,
                33L,
                "ONLY_NEW",
                null
        ));

        assertThat(result.httpStatusCode()).isEqualTo(200);
        assertThat(result.cancelResult()).isEqualTo("SUCCESS");
        assertThat(result.newOrderResult()).isEqualTo("SUCCESS");
        assertThat(result.cancelResponse().status()).isEqualTo("CANCELED");
        assertThat(result.newOrderResponse().orderId()).isEqualTo(34L);
        assertThat(result.newOrderResponse().updateTime()).isEqualTo(1660813156959L);
        assertThat(result.cancelError()).isNull();
        assertThat(result.newOrderError()).isNull();
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.uri()).contains("/api/v3/order/cancelReplace?symbol=BTCUSDT&side=BUY");
            assertThat(call.uri()).contains("cancelReplaceMode=STOP_ON_FAILURE");
            assertThat(call.uri()).doesNotContain("test-secret");
        });
    }

    @Test
    void preserves_spot_cancel_replace_partial_failure_body() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(409, """
                {
                  "code": -2021,
                  "msg": "Order cancel-replace partially failed.",
                  "data": {
                    "cancelResult": "SUCCESS",
                    "newOrderResult": "FAILURE",
                    "cancelResponse": {
                      "symbol": "BTCUSDT",
                      "origClientOrderId": "old-1",
                      "orderId": 33,
                      "clientOrderId": "cancel-1",
                      "price": "50000.00000000",
                      "origQty": "0.00100000",
                      "executedQty": "0.00000000",
                      "cummulativeQuoteQty": "0.00000000",
                      "status": "CANCELED",
                      "timeInForce": "GTC",
                      "type": "LIMIT",
                      "side": "BUY"
                    },
                    "newOrderResponse": {
                      "code": -2010,
                      "msg": "Order would immediately match and take."
                    }
                  }
                }
                """));
        BinanceOrderClient client = spotClient(transport);

        BinanceCancelReplaceResult result = client.cancelReplace(new BinanceCancelReplaceCommand(
                spotLimitOrder("replace-1"),
                "ALLOW_FAILURE",
                "cancel-1",
                null,
                33L,
                null,
                "CANCEL_ONLY"
        ));

        assertThat(result.httpStatusCode()).isEqualTo(409);
        assertThat(result.exchangeCode()).isEqualTo(-2021);
        assertThat(result.exchangeMessage()).contains("partially failed");
        assertThat(result.cancelResult()).isEqualTo("SUCCESS");
        assertThat(result.newOrderResult()).isEqualTo("FAILURE");
        assertThat(result.cancelResponse().status()).isEqualTo("CANCELED");
        assertThat(result.newOrderResponse()).isNull();
        assertThat(result.newOrderError().code()).isEqualTo(-2010);
        assertThat(result.newOrderError().message()).contains("immediately match");
    }

    @Test
    void places_spot_sor_order_and_parses_sor_fields() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                {
                  "symbol": "BTCUSDT",
                  "orderId": 2,
                  "orderListId": -1,
                  "clientOrderId": "sor-1",
                  "transactTime": 1689149087774,
                  "price": "31000.00000000",
                  "origQty": "0.50000000",
                  "executedQty": "0.50000000",
                  "origQuoteOrderQty": "0.000000",
                  "cummulativeQuoteQty": "14000.00000000",
                  "status": "FILLED",
                  "timeInForce": "GTC",
                  "type": "LIMIT",
                  "side": "BUY",
                  "workingTime": 1689149087774,
                  "fills": [
                    {
                      "matchType": "ONE_PARTY_TRADE_REPORT",
                      "price": "28000.00000000",
                      "qty": "0.50000000",
                      "commission": "0.00000000",
                      "commissionAsset": "BTC",
                      "tradeId": -1,
                      "allocId": 0
                    }
                  ],
                  "workingFloor": "SOR",
                  "selfTradePreventionMode": "NONE",
                  "usedSor": true
                }
                """));
        BinanceOrderClient client = spotClient(transport);

        BinanceSorOrderResult result = client.placeSorOrder(spotLimitOrder("sor-1"));

        assertThat(result.orderId()).isEqualTo(2L);
        assertThat(result.status()).isEqualTo("FILLED");
        assertThat(result.usedSor()).isTrue();
        assertThat(result.workingFloor()).isEqualTo("SOR");
        assertThat(result.fills()).singleElement().satisfies(fill -> {
            assertThat(fill.matchType()).isEqualTo("ONE_PARTY_TRADE_REPORT");
            assertThat(fill.price()).isEqualByComparingTo("28000.00000000");
            assertThat(fill.quantity()).isEqualByComparingTo("0.50000000");
            assertThat(fill.allocationId()).isEqualTo(0L);
        });
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.uri()).contains("/api/v3/sor/order?symbol=BTCUSDT&side=BUY");
            assertThat(call.uri()).doesNotContain("test-secret");
        });
    }

    @Test
    void tests_spot_sor_order_with_commission_rates() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                {
                  "standardCommissionForOrder": {
                    "maker": "0.00000112",
                    "taker": "0.00000114"
                  },
                  "taxCommissionForOrder": {
                    "maker": "0.00000112",
                    "taker": "0.00000114"
                  },
                  "discount": {
                    "enabledForAccount": true,
                    "enabledForSymbol": true,
                    "discountAsset": "BNB",
                    "discount": "0.25000000"
                  }
                }
                """));
        BinanceOrderClient client = spotClient(transport);

        BinanceSorTestOrderResult result = client.testSorOrder(spotLimitOrder("sor-test-1"), true);

        assertThat(result.standardCommissionForOrder().maker()).isEqualByComparingTo("0.00000112");
        assertThat(result.taxCommissionForOrder().taker()).isEqualByComparingTo("0.00000114");
        assertThat(result.discount().discountAsset()).isEqualTo("BNB");
        assertThat(result.discount().discount()).isEqualByComparingTo("0.25000000");
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.uri()).contains("/api/v3/sor/order/test?symbol=BTCUSDT&side=BUY");
            assertThat(call.uri()).contains("computeCommissionRates=true");
        });
    }

    @Test
    void places_spot_oco_order_list_and_parses_reports() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                {
                  "orderListId": 1,
                  "contingencyType": "OCO",
                  "listStatusType": "EXEC_STARTED",
                  "listOrderStatus": "EXECUTING",
                  "listClientOrderId": "oco-list-1",
                  "transactionTime": 1710485608839,
                  "symbol": "BTCUSDT",
                  "orders": [
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 10,
                      "clientOrderId": "oco-above-1"
                    },
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 11,
                      "clientOrderId": "oco-below-1"
                    }
                  ],
                  "orderReports": [
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 10,
                      "orderListId": 1,
                      "clientOrderId": "oco-above-1",
                      "transactTime": 1710485608839,
                      "price": "52000.00000000",
                      "origQty": "0.01000000",
                      "executedQty": "0.00000000",
                      "origQuoteOrderQty": "0.000000",
                      "cummulativeQuoteQty": "0.00000000",
                      "status": "NEW",
                      "timeInForce": "GTC",
                      "type": "LIMIT_MAKER",
                      "side": "SELL",
                      "workingTime": 1710485608839,
                      "selfTradePreventionMode": "NONE"
                    },
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 11,
                      "orderListId": 1,
                      "clientOrderId": "oco-below-1",
                      "transactTime": 1710485608839,
                      "price": "48000.00000000",
                      "origQty": "0.01000000",
                      "executedQty": "0.00000000",
                      "origQuoteOrderQty": "0.000000",
                      "cummulativeQuoteQty": "0.00000000",
                      "status": "NEW",
                      "timeInForce": "GTC",
                      "type": "STOP_LOSS_LIMIT",
                      "side": "SELL",
                      "stopPrice": "48100.00000000",
                      "workingTime": -1,
                      "selfTradePreventionMode": "NONE"
                    }
                  ]
                }
                """));
        BinanceOrderClient client = spotClient(transport);

        BinanceOrderListResult result = client.placeOcoOrderList(ocoOrderList());

        assertThat(result.orderListId()).isEqualTo(1L);
        assertThat(result.contingencyType()).isEqualTo("OCO");
        assertThat(result.listOrderStatus()).isEqualTo("EXECUTING");
        assertThat(result.orders()).extracting(BinanceOrderListOrder::clientOrderId)
                .containsExactly("oco-above-1", "oco-below-1");
        assertThat(result.orderReports()).hasSize(2);
        assertThat(result.orderReports().get(1)).satisfies(report -> {
            assertThat(report.type()).isEqualTo("STOP_LOSS_LIMIT");
            assertThat(report.stopPrice()).isEqualByComparingTo("48100.00000000");
            assertThat(report.cumulativeQuoteQuantity()).isEqualByComparingTo("0.00000000");
        });
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.uri()).contains("/api/v3/orderList/oco?symbol=BTCUSDT");
            assertThat(call.uri()).contains("aboveType=LIMIT_MAKER");
            assertThat(call.uri()).contains("belowType=STOP_LOSS_LIMIT");
        });
    }

    @Test
    void places_spot_oto_order_list_and_parses_reports() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                {
                  "orderListId": 2,
                  "contingencyType": "OTO",
                  "listStatusType": "EXEC_STARTED",
                  "listOrderStatus": "EXECUTING",
                  "listClientOrderId": "oto-list-1",
                  "transactionTime": 1712289389158,
                  "symbol": "BTCUSDT",
                  "orders": [
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 20,
                      "clientOrderId": "oto-working-1"
                    },
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 21,
                      "clientOrderId": "oto-pending-1"
                    }
                  ],
                  "orderReports": [
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 20,
                      "orderListId": 2,
                      "clientOrderId": "oto-working-1",
                      "transactTime": 1712289389158,
                      "price": "52000.00000000",
                      "origQty": "0.01000000",
                      "executedQty": "0.00000000",
                      "origQuoteOrderQty": "0.000000",
                      "cummulativeQuoteQty": "0.00000000",
                      "status": "NEW",
                      "timeInForce": "GTC",
                      "type": "LIMIT",
                      "side": "SELL",
                      "workingTime": 1712289389158,
                      "selfTradePreventionMode": "NONE"
                    },
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 21,
                      "orderListId": 2,
                      "clientOrderId": "oto-pending-1",
                      "transactTime": 1712289389158,
                      "price": "48000.00000000",
                      "origQty": "0.01000000",
                      "executedQty": "0.00000000",
                      "origQuoteOrderQty": "0.000000",
                      "cummulativeQuoteQty": "0.00000000",
                      "status": "PENDING_NEW",
                      "timeInForce": "GTC",
                      "type": "STOP_LOSS_LIMIT",
                      "side": "BUY",
                      "stopPrice": "48100.00000000",
                      "workingTime": -1,
                      "selfTradePreventionMode": "NONE"
                    }
                  ]
                }
                """));
        BinanceOrderClient client = spotClient(transport);

        BinanceOrderListResult result = client.placeOtoOrderList(otoOrderList());

        assertThat(result.orderListId()).isEqualTo(2L);
        assertThat(result.contingencyType()).isEqualTo("OTO");
        assertThat(result.orders()).extracting(BinanceOrderListOrder::clientOrderId)
                .containsExactly("oto-working-1", "oto-pending-1");
        assertThat(result.orderReports()).hasSize(2);
        assertThat(result.orderReports().get(1)).satisfies(report -> {
            assertThat(report.status()).isEqualTo("PENDING_NEW");
            assertThat(report.type()).isEqualTo("STOP_LOSS_LIMIT");
            assertThat(report.stopPrice()).isEqualByComparingTo("48100.00000000");
        });
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.uri()).contains("/api/v3/orderList/oto?symbol=BTCUSDT");
            assertThat(call.uri()).contains("workingType=LIMIT");
            assertThat(call.uri()).contains("pendingType=STOP_LOSS_LIMIT");
        });
    }

    @Test
    void places_spot_otoco_order_list_and_parses_reports() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                {
                  "orderListId": 3,
                  "contingencyType": "OTO",
                  "listStatusType": "EXEC_STARTED",
                  "listOrderStatus": "EXECUTING",
                  "listClientOrderId": "otoco-list-1",
                  "transactionTime": 1712291372842,
                  "symbol": "BTCUSDT",
                  "orders": [
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 30,
                      "clientOrderId": "otoco-working-1"
                    },
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 31,
                      "clientOrderId": "otoco-above-1"
                    },
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 32,
                      "clientOrderId": "otoco-below-1"
                    }
                  ],
                  "orderReports": [
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 30,
                      "orderListId": 3,
                      "clientOrderId": "otoco-working-1",
                      "transactTime": 1712291372842,
                      "price": "52000.00000000",
                      "origQty": "0.01000000",
                      "executedQty": "0.00000000",
                      "origQuoteOrderQty": "0.000000",
                      "cummulativeQuoteQty": "0.00000000",
                      "status": "NEW",
                      "timeInForce": "GTC",
                      "type": "LIMIT",
                      "side": "SELL",
                      "workingTime": 1712291372842,
                      "selfTradePreventionMode": "NONE"
                    },
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 31,
                      "orderListId": 3,
                      "clientOrderId": "otoco-above-1",
                      "transactTime": 1712291372842,
                      "price": "53000.00000000",
                      "origQty": "0.01000000",
                      "executedQty": "0.00000000",
                      "origQuoteOrderQty": "0.000000",
                      "cummulativeQuoteQty": "0.00000000",
                      "status": "PENDING_NEW",
                      "timeInForce": "GTC",
                      "type": "LIMIT_MAKER",
                      "side": "BUY",
                      "workingTime": -1,
                      "selfTradePreventionMode": "NONE"
                    },
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 32,
                      "orderListId": 3,
                      "clientOrderId": "otoco-below-1",
                      "transactTime": 1712291372842,
                      "price": "48000.00000000",
                      "origQty": "0.01000000",
                      "executedQty": "0.00000000",
                      "origQuoteOrderQty": "0.000000",
                      "cummulativeQuoteQty": "0.00000000",
                      "status": "PENDING_NEW",
                      "timeInForce": "GTC",
                      "type": "STOP_LOSS_LIMIT",
                      "side": "BUY",
                      "stopPrice": "48100.00000000",
                      "workingTime": -1,
                      "selfTradePreventionMode": "NONE"
                    }
                  ]
                }
                """));
        BinanceOrderClient client = spotClient(transport);

        BinanceOrderListResult result = client.placeOtocoOrderList(otocoOrderList());

        assertThat(result.orderListId()).isEqualTo(3L);
        assertThat(result.orders()).extracting(BinanceOrderListOrder::clientOrderId)
                .containsExactly("otoco-working-1", "otoco-above-1", "otoco-below-1");
        assertThat(result.orderReports()).hasSize(3);
        assertThat(result.orderReports().get(2)).satisfies(report -> {
            assertThat(report.status()).isEqualTo("PENDING_NEW");
            assertThat(report.type()).isEqualTo("STOP_LOSS_LIMIT");
            assertThat(report.stopPrice()).isEqualByComparingTo("48100.00000000");
        });
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.uri()).contains("/api/v3/orderList/otoco?symbol=BTCUSDT");
            assertThat(call.uri()).contains("pendingAboveType=LIMIT_MAKER");
            assertThat(call.uri()).contains("pendingBelowType=STOP_LOSS_LIMIT");
        });
    }

    @Test
    void places_spot_opo_order_list_and_parses_reports() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                {
                  "orderListId": 4,
                  "contingencyType": "OPO",
                  "listStatusType": "EXEC_STARTED",
                  "listOrderStatus": "EXECUTING",
                  "listClientOrderId": "opo-list-1",
                  "transactionTime": 1712293372842,
                  "symbol": "BTCUSDT",
                  "orders": [
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 40,
                      "clientOrderId": "opo-working-1"
                    },
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 41,
                      "clientOrderId": "opo-pending-1"
                    }
                  ],
                  "orderReports": [
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 40,
                      "orderListId": 4,
                      "clientOrderId": "opo-working-1",
                      "transactTime": 1712293372842,
                      "price": "52000.00000000",
                      "origQty": "0.01000000",
                      "executedQty": "0.00000000",
                      "origQuoteOrderQty": "0.000000",
                      "cummulativeQuoteQty": "0.00000000",
                      "status": "NEW",
                      "timeInForce": "GTC",
                      "type": "LIMIT",
                      "side": "SELL",
                      "workingTime": 1712293372842,
                      "selfTradePreventionMode": "NONE"
                    },
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 41,
                      "orderListId": 4,
                      "clientOrderId": "opo-pending-1",
                      "transactTime": 1712293372842,
                      "price": "48000.00000000",
                      "origQty": "0.00000000",
                      "executedQty": "0.00000000",
                      "origQuoteOrderQty": "0.000000",
                      "cummulativeQuoteQty": "0.00000000",
                      "status": "PENDING_NEW",
                      "timeInForce": "GTC",
                      "type": "STOP_LOSS_LIMIT",
                      "side": "BUY",
                      "stopPrice": "48100.00000000",
                      "workingTime": -1,
                      "selfTradePreventionMode": "NONE"
                    }
                  ]
                }
                """));
        BinanceOrderClient client = spotClient(transport);

        BinanceOrderListResult result = client.placeOpoOrderList(opoOrderList());

        assertThat(result.orderListId()).isEqualTo(4L);
        assertThat(result.contingencyType()).isEqualTo("OPO");
        assertThat(result.orders()).extracting(BinanceOrderListOrder::clientOrderId)
                .containsExactly("opo-working-1", "opo-pending-1");
        assertThat(result.orderReports()).hasSize(2);
        assertThat(result.orderReports().get(1)).satisfies(report -> {
            assertThat(report.status()).isEqualTo("PENDING_NEW");
            assertThat(report.type()).isEqualTo("STOP_LOSS_LIMIT");
            assertThat(report.stopPrice()).isEqualByComparingTo("48100.00000000");
        });
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.uri()).contains("/api/v3/orderList/opo?symbol=BTCUSDT");
            assertThat(call.uri()).contains("workingType=LIMIT");
            assertThat(call.uri()).contains("pendingType=STOP_LOSS_LIMIT");
            assertThat(call.uri()).doesNotContain("pendingQuantity");
        });
    }

    @Test
    void places_spot_opoco_order_list_and_parses_reports() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                {
                  "orderListId": 5,
                  "contingencyType": "OTO",
                  "listStatusType": "EXEC_STARTED",
                  "listOrderStatus": "EXECUTING",
                  "listClientOrderId": "opoco-list-1",
                  "transactionTime": 1712295372842,
                  "symbol": "BTCUSDT",
                  "orders": [
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 50,
                      "clientOrderId": "opoco-working-1"
                    },
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 51,
                      "clientOrderId": "opoco-above-1"
                    },
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 52,
                      "clientOrderId": "opoco-below-1"
                    }
                  ],
                  "orderReports": [
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 50,
                      "orderListId": 5,
                      "clientOrderId": "opoco-working-1",
                      "transactTime": 1712295372842,
                      "price": "52000.00000000",
                      "origQty": "0.01000000",
                      "executedQty": "0.00000000",
                      "origQuoteOrderQty": "0.000000",
                      "cummulativeQuoteQty": "0.00000000",
                      "status": "NEW",
                      "timeInForce": "GTC",
                      "type": "LIMIT",
                      "side": "SELL",
                      "workingTime": 1712295372842,
                      "selfTradePreventionMode": "NONE"
                    },
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 51,
                      "orderListId": 5,
                      "clientOrderId": "opoco-above-1",
                      "transactTime": 1712295372842,
                      "price": "53000.00000000",
                      "executedQty": "0.00000000",
                      "origQuoteOrderQty": "0.00000000",
                      "cummulativeQuoteQty": "0.00000000",
                      "status": "PENDING_NEW",
                      "timeInForce": "GTC",
                      "type": "LIMIT_MAKER",
                      "side": "BUY",
                      "workingTime": -1,
                      "selfTradePreventionMode": "NONE"
                    },
                    {
                      "symbol": "BTCUSDT",
                      "orderId": 52,
                      "orderListId": 5,
                      "clientOrderId": "opoco-below-1",
                      "transactTime": 1712295372842,
                      "price": "48000.00000000",
                      "executedQty": "0.00000000",
                      "origQuoteOrderQty": "0.00000000",
                      "cummulativeQuoteQty": "0.00000000",
                      "status": "PENDING_NEW",
                      "timeInForce": "GTC",
                      "type": "STOP_LOSS_LIMIT",
                      "side": "BUY",
                      "stopPrice": "48100.00000000",
                      "workingTime": -1,
                      "selfTradePreventionMode": "NONE"
                    }
                  ]
                }
                """));
        BinanceOrderClient client = spotClient(transport);

        BinanceOrderListResult result = client.placeOpocoOrderList(opocoOrderList());

        assertThat(result.orderListId()).isEqualTo(5L);
        assertThat(result.contingencyType()).isEqualTo("OTO");
        assertThat(result.orders()).extracting(BinanceOrderListOrder::clientOrderId)
                .containsExactly("opoco-working-1", "opoco-above-1", "opoco-below-1");
        assertThat(result.orderReports()).hasSize(3);
        assertThat(result.orderReports().get(2)).satisfies(report -> {
            assertThat(report.status()).isEqualTo("PENDING_NEW");
            assertThat(report.type()).isEqualTo("STOP_LOSS_LIMIT");
            assertThat(report.stopPrice()).isEqualByComparingTo("48100.00000000");
        });
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.uri()).contains("/api/v3/orderList/opoco?symbol=BTCUSDT");
            assertThat(call.uri()).contains("pendingAboveType=LIMIT_MAKER");
            assertThat(call.uri()).contains("pendingBelowType=STOP_LOSS_LIMIT");
            assertThat(call.uri()).doesNotContain("pendingQuantity");
        });
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

    private BinanceOrderClient spotClient(FakeTransport transport) {
        return new BinanceOrderClient(
                spotBinance(),
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

    private BinanceOrderCommand spotLimitOrder(String clientOrderId) {
        return new BinanceOrderCommand(
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
                null,
                null,
                null,
                clientOrderId,
                null,
                new BigDecimal("0.001"),
                null,
                new BigDecimal("49900"),
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

    private BinanceModifyOrderCommand modifyOrder() {
        return new BinanceModifyOrderCommand(
                "BTCUSDT",
                12345L,
                null,
                "BUY",
                new BigDecimal("0.001"),
                new BigDecimal("50000"),
                null
        );
    }

    private BinanceOcoOrderListCommand ocoOrderList() {
        return new BinanceOcoOrderListCommand(
                "BTCUSDT",
                "oco-list-1",
                "SELL",
                new BigDecimal("0.01"),
                "LIMIT_MAKER",
                "oco-above-1",
                null,
                new BigDecimal("52000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "STOP_LOSS_LIMIT",
                "oco-below-1",
                null,
                new BigDecimal("48000"),
                new BigDecimal("48100"),
                null,
                "GTC",
                null,
                null,
                null,
                null,
                null,
                "RESULT",
                "NONE"
        );
    }

    private BinanceOtoOrderListCommand otoOrderList() {
        return new BinanceOtoOrderListCommand(
                "BTCUSDT",
                "oto-list-1",
                "RESULT",
                "NONE",
                "LIMIT",
                "SELL",
                "oto-working-1",
                new BigDecimal("52000"),
                new BigDecimal("0.01"),
                null,
                "GTC",
                null,
                null,
                null,
                null,
                null,
                "STOP_LOSS_LIMIT",
                "BUY",
                "oto-pending-1",
                new BigDecimal("48000"),
                new BigDecimal("48100"),
                null,
                new BigDecimal("0.01"),
                null,
                "GTC",
                null,
                null,
                null,
                null,
                null
        );
    }

    private BinanceOtocoOrderListCommand otocoOrderList() {
        return new BinanceOtocoOrderListCommand(
                "BTCUSDT",
                "otoco-list-1",
                "RESULT",
                "NONE",
                "LIMIT",
                "SELL",
                "otoco-working-1",
                new BigDecimal("52000"),
                new BigDecimal("0.01"),
                null,
                "GTC",
                null,
                null,
                null,
                null,
                null,
                "BUY",
                new BigDecimal("0.01"),
                "LIMIT_MAKER",
                "otoco-above-1",
                new BigDecimal("53000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "STOP_LOSS_LIMIT",
                "otoco-below-1",
                new BigDecimal("48000"),
                new BigDecimal("48100"),
                null,
                null,
                "GTC",
                null,
                null,
                null,
                null,
                null
        );
    }

    private BinanceOpoOrderListCommand opoOrderList() {
        return new BinanceOpoOrderListCommand(
                "BTCUSDT",
                "opo-list-1",
                "RESULT",
                "NONE",
                "LIMIT",
                "SELL",
                "opo-working-1",
                new BigDecimal("52000"),
                new BigDecimal("0.01"),
                null,
                "GTC",
                null,
                null,
                null,
                null,
                null,
                "STOP_LOSS_LIMIT",
                "BUY",
                "opo-pending-1",
                new BigDecimal("48000"),
                new BigDecimal("48100"),
                null,
                null,
                "GTC",
                null,
                null,
                null,
                null,
                null
        );
    }

    private BinanceOpocoOrderListCommand opocoOrderList() {
        return new BinanceOpocoOrderListCommand(
                "BTCUSDT",
                "opoco-list-1",
                "RESULT",
                "NONE",
                "LIMIT",
                "SELL",
                "opoco-working-1",
                new BigDecimal("52000"),
                new BigDecimal("0.01"),
                null,
                "GTC",
                null,
                null,
                null,
                null,
                null,
                "BUY",
                "LIMIT_MAKER",
                "opoco-above-1",
                new BigDecimal("53000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "STOP_LOSS_LIMIT",
                "opoco-below-1",
                new BigDecimal("48000"),
                new BigDecimal("48100"),
                null,
                null,
                "GTC",
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
