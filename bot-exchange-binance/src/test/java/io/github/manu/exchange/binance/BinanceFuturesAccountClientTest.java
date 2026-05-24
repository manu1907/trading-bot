package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceFuturesAccountClientTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_499_827_319_559L),
            ZoneOffset.UTC
    );

    @Test
    void changes_position_mode_margin_type_and_multi_assets_mode() {
        FakeTransport transport = new FakeTransport(
                ack(),
                ack(),
                ack()
        );
        BinanceFuturesAccountClient client = client(transport);

        assertThat(client.changePositionMode("HEDGE").message()).isEqualTo("success");
        assertThat(client.changeMarginType("BTCUSDT", "ISOLATED").code()).isEqualTo(200);
        assertThat(client.changeMultiAssetsMode(false).code()).isEqualTo(200);

        assertThat(transport.calls()).extracting(FakeCall::method).containsExactly("POST", "POST", "POST");
        assertThat(transport.calls()).extracting(FakeCall::uri)
                .anySatisfy(uri -> assertThat(uri).contains("/fapi/v1/positionSide/dual?dualSidePosition=true"))
                .anySatisfy(uri -> assertThat(uri).contains("/fapi/v1/marginType?symbol=BTCUSDT&marginType=ISOLATED"))
                .anySatisfy(uri -> assertThat(uri).contains("/fapi/v1/multiAssetsMargin?multiAssetsMargin=false"));
    }

    @Test
    void changes_initial_leverage_and_parses_usdm_response() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                {
                  "leverage": 21,
                  "maxNotionalValue": "1000000",
                  "symbol": "BTCUSDT"
                }
                """, Map.of(
                "X-MBX-USED-WEIGHT-1M", List.of("1"),
                "X-MBX-ORDER-COUNT-10S", List.of("1")
        )));
        BinanceFuturesAccountClient client = client(transport);

        BinanceFuturesLeverageResult result = client.changeInitialLeverage("BTCUSDT", 21);

        assertThat(result.symbol()).isEqualTo("BTCUSDT");
        assertThat(result.leverage()).isEqualTo(21);
        assertThat(result.maxNotionalValue()).isEqualByComparingTo("1000000");
        assertThat(result.maxQty()).isNull();
        assertThat(client.currentRateLimitUsage()).hasValueSatisfying(usage -> {
            assertThat(usage.usedWeights()).containsEntry("X-MBX-USED-WEIGHT-1M", 1L);
            assertThat(usage.orderCounts()).containsEntry("X-MBX-ORDER-COUNT-10S", 1L);
        });
    }

    @Test
    void reads_balances_account_info_and_position_risk() {
        FakeTransport transport = new FakeTransport(
                new BinanceHttpResponse(200, """
                        [
                          {
                            "accountAlias": "SgsR",
                            "asset": "USDT",
                            "balance": "122607.35137903",
                            "crossWalletBalance": "23.72469206",
                            "crossUnPnl": "0.00000000",
                            "availableBalance": "23.72469206",
                            "maxWithdrawAmount": "23.72469206",
                            "marginAvailable": true,
                            "updateTime": 1617939110373
                          }
                        ]
                        """),
                new BinanceHttpResponse(200, """
                        {
                          "totalWalletBalance": "103.12345678",
                          "availableBalance": "103.12345678",
                          "assets": [
                            {
                              "asset": "USDT",
                              "walletBalance": "23.72469206",
                              "unrealizedProfit": "0.00000000",
                              "marginBalance": "23.72469206",
                              "crossUnPnl": "0.00000000",
                              "availableBalance": "23.72469206",
                              "updateTime": 1625474304765
                            }
                          ],
                          "positions": [
                            {
                              "symbol": "BTCUSDT",
                              "positionSide": "BOTH",
                              "positionAmt": "1.000",
                              "entryPrice": "30000.0",
                              "unrealizedProfit": "0.00000000",
                              "initialMargin": "0",
                              "maintMargin": "0",
                              "updateTime": 0
                            }
                          ]
                        }
                        """),
                new BinanceHttpResponse(200, """
                        [
                          {
                            "symbol": "ADAUSDT",
                            "positionSide": "BOTH",
                            "positionAmt": "30",
                            "entryPrice": "0.385",
                            "breakEvenPrice": "0.385077",
                            "markPrice": "0.41047590",
                            "unRealizedProfit": "0.76427700",
                            "liquidationPrice": "0",
                            "notional": "12.31427700",
                            "marginAsset": "USDT",
                            "adl": 2,
                            "updateTime": 1720736417660
                          }
                        ]
                        """)
        );
        BinanceFuturesAccountClient client = client(transport);

        List<BinanceFuturesBalance> balances = client.balances();
        BinanceFuturesAccountSnapshot account = client.accountInfo();
        List<BinanceFuturesPositionSnapshot> positions = client.positionRisk(
                new BinanceFuturesPositionRiskQuery("ADAUSDT", null, null)
        );

        assertThat(balances).singleElement().satisfies(balance -> {
            assertThat(balance.asset()).isEqualTo("USDT");
            assertThat(balance.balance()).isEqualByComparingTo("122607.35137903");
            assertThat(balance.marginAvailable()).isTrue();
        });
        assertThat(account.totalWalletBalance()).isEqualByComparingTo("103.12345678");
        assertThat(account.assets()).singleElement().extracting(BinanceFuturesAssetSnapshot::asset).isEqualTo("USDT");
        assertThat(account.positions()).singleElement().extracting(BinanceFuturesPositionSnapshot::symbol).isEqualTo("BTCUSDT");
        assertThat(positions).singleElement().satisfies(position -> {
            assertThat(position.symbol()).isEqualTo("ADAUSDT");
            assertThat(position.unrealizedProfit()).isEqualByComparingTo("0.76427700");
            assertThat(position.adl()).isEqualTo(2);
        });
        assertThat(transport.calls()).extracting(FakeCall::method).containsExactly("GET", "GET", "GET");
    }

    @Test
    void reads_adl_quantiles_and_force_orders() {
        FakeTransport transport = new FakeTransport(
                new BinanceHttpResponse(200, """
                        [
                          {
                            "symbol": "BTCUSDT",
                            "adlQuantile": {
                              "LONG": 1,
                              "SHORT": 2,
                              "BOTH": 0
                            }
                          }
                        ]
                        """),
                new BinanceHttpResponse(200, """
                        [
                          {
                            "orderId": 6071832819,
                            "symbol": "BTCUSDT",
                            "status": "FILLED",
                            "clientOrderId": "autoclose-1596107620040000020",
                            "price": "10871.09",
                            "avgPrice": "10913.21000",
                            "origQty": "0.001",
                            "executedQty": "0.001",
                            "cumQuote": "10.91321",
                            "timeInForce": "IOC",
                            "type": "LIMIT",
                            "reduceOnly": false,
                            "closePosition": false,
                            "side": "SELL",
                            "positionSide": "BOTH",
                            "stopPrice": "0",
                            "workingType": "CONTRACT_PRICE",
                            "origType": "LIMIT",
                            "time": 1596107620044,
                            "updateTime": 1596107620087
                          }
                        ]
                        """)
        );
        BinanceFuturesAccountClient client = client(transport);

        List<BinanceFuturesAdlQuantile> quantiles = client.adlQuantiles("BTCUSDT");
        List<BinanceFuturesForceOrder> forceOrders = client.forceOrders(
                new BinanceFuturesForceOrderQuery("BTCUSDT", "LIQUIDATION", null, null, 50)
        );

        assertThat(quantiles).singleElement().satisfies(quantile -> {
            assertThat(quantile.symbol()).isEqualTo("BTCUSDT");
            assertThat(quantile.quantiles()).containsEntry("LONG", 1).containsEntry("SHORT", 2);
        });
        assertThat(forceOrders).singleElement().satisfies(order -> {
            assertThat(order.orderId()).isEqualTo(6071832819L);
            assertThat(order.cumulativeQuote()).isEqualByComparingTo("10.91321");
            assertThat(order.reduceOnly()).isFalse();
        });
        assertThat(transport.calls()).extracting(FakeCall::method).containsExactly("GET", "GET");
    }

    @Test
    void reads_income_and_funding_rates() {
        FakeTransport transport = new FakeTransport(
                new BinanceHttpResponse(200, """
                        [
                          {
                            "symbol": "BTCUSDT",
                            "incomeType": "FUNDING_FEE",
                            "income": "-0.37500000",
                            "asset": "USDT",
                            "info": "FUNDING_FEE",
                            "time": 1570608000000,
                            "tranId": 9689322392,
                            "tradeId": "2059192"
                          }
                        ]
                        """),
                new BinanceHttpResponse(200, """
                        [
                          {
                            "symbol": "BTCUSDT",
                            "fundingRate": "0.00010000",
                            "fundingTime": 1570636800000,
                            "markPrice": "34287.54619963"
                          }
                        ]
                        """)
        );
        BinanceFuturesAccountClient client = client(transport);

        List<BinanceFuturesIncome> income = client.income(
                new BinanceFuturesIncomeQuery("BTCUSDT", "FUNDING_FEE", null, null, null, 100)
        );
        List<BinanceFuturesFundingRate> fundingRates = client.fundingRates(
                new BinanceFuturesFundingRateQuery("BTCUSDT", null, null, 100)
        );

        assertThat(income).singleElement().satisfies(item -> {
            assertThat(item.symbol()).isEqualTo("BTCUSDT");
            assertThat(item.incomeType()).isEqualTo("FUNDING_FEE");
            assertThat(item.income()).isEqualByComparingTo("-0.37500000");
            assertThat(item.transactionId()).isEqualTo("9689322392");
        });
        assertThat(fundingRates).singleElement().satisfies(rate -> {
            assertThat(rate.symbol()).isEqualTo("BTCUSDT");
            assertThat(rate.fundingRate()).isEqualByComparingTo("0.00010000");
            assertThat(rate.markPrice()).isEqualByComparingTo("34287.54619963");
        });
        assertThat(transport.calls()).extracting(FakeCall::method).containsExactly("GET", "GET");
        assertThat(transport.calls()).extracting(FakeCall::uri)
                .anySatisfy(uri -> assertThat(uri).contains("/fapi/v1/income?symbol=BTCUSDT&incomeType=FUNDING_FEE"))
                .anySatisfy(uri -> assertThat(uri).contains("/fapi/v1/fundingRate?symbol=BTCUSDT&limit=100"));
    }

    @Test
    void throws_sanitized_binance_api_exception_for_exchange_error() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(400, """
                {"code": -4046, "msg": "No need to change margin type."}
                """));
        BinanceFuturesAccountClient client = client(transport);

        assertThatThrownBy(() -> client.changeMarginType("BTCUSDT", "ISOLATED"))
                .isInstanceOf(BinanceApiException.class)
                .hasMessageContaining("httpStatusCode=400")
                .hasMessageContaining("exchangeCode=-4046")
                .hasMessageContaining("No need to change margin type");
    }

    private BinanceFuturesAccountClient client(FakeTransport transport) {
        return new BinanceFuturesAccountClient(
                binance(),
                "api-key",
                "test-secret",
                FIXED_CLOCK,
                0,
                transport,
                JsonMapperFactory.create()
        );
    }

    private BinanceHttpResponse ack() {
        return new BinanceHttpResponse(200, """
                {
                  "code": 200,
                  "msg": "success"
                }
                """);
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
            calls.add(new FakeCall(method, uri.toString()));
            return responses.removeFirst();
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
