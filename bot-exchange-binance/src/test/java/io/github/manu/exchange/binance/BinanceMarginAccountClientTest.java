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

class BinanceMarginAccountClientTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_499_827_319_559L),
            ZoneOffset.UTC
    );

    @Test
    void sends_margin_borrow_repay_and_parses_transaction_id() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                {
                  "tranId": 100000001
                }
                """, Map.of(
                "X-MBX-USED-WEIGHT-1M", List.of("1500")
        )));
        BinanceMarginAccountClient client = client(transport);

        BinanceMarginBorrowRepayResult result = client.borrowRepay(
                new BinanceMarginBorrowRepayCommand("USDT", new BigDecimal("25.5000"), "BORROW", false, null)
        );

        assertThat(result.transactionId()).isEqualTo(100000001L);
        assertThat(transport.calls()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.uri()).contains("/sapi/v1/margin/borrow-repay?asset=USDT&isIsolated=FALSE");
            assertThat(call.apiKey()).isEqualTo("api-key");
            assertThat(call.apiKeyHeader()).isEqualTo("X-MBX-APIKEY");
        });
        assertThat(client.currentRateLimitUsage()).hasValueSatisfying(usage ->
                assertThat(usage.usedWeights()).containsEntry("X-MBX-USED-WEIGHT-1M", 1500L));
    }

    @Test
    void reads_transfer_history_and_max_transferable() {
        FakeTransport transport = new FakeTransport(
                new BinanceHttpResponse(200, """
                        {
                          "rows": [
                            {
                              "amount": "5.00000000",
                              "asset": "USDT",
                              "status": "CONFIRMED",
                              "timestamp": 1566888436,
                              "txId": 5239810406,
                              "type": "ROLL_OUT",
                              "transFrom": "ISOLATED_MARGIN",
                              "transTo": "ISOLATED_MARGIN",
                              "fromSymbol": "BNBUSDT",
                              "toSymbol": "BTCUSDT"
                            }
                          ],
                          "total": 1
                        }
                        """),
                new BinanceHttpResponse(200, """
                        {
                          "amount": "3.59498107"
                        }
                        """)
        );
        BinanceMarginAccountClient client = client(transport);

        BinanceMarginTransferHistoryPage history = client.transferHistory(new BinanceMarginTransferHistoryQuery(
                "USDT",
                "ROLL_OUT",
                null,
                null,
                1L,
                10L,
                "BTCUSDT"
        ));
        BinanceMarginMaxTransferable maxTransferable = client.maxTransferable("USDT", "BTCUSDT");

        assertThat(history.total()).isEqualTo(1);
        assertThat(history.rows()).singleElement().satisfies(record -> {
            assertThat(record.amount()).isEqualByComparingTo("5.00000000");
            assertThat(record.asset()).isEqualTo("USDT");
            assertThat(record.transactionId()).isEqualTo(5239810406L);
            assertThat(record.type()).isEqualTo("ROLL_OUT");
            assertThat(record.fromSymbol()).isEqualTo("BNBUSDT");
            assertThat(record.toSymbol()).isEqualTo("BTCUSDT");
        });
        assertThat(maxTransferable.amount()).isEqualByComparingTo("3.59498107");
        assertThat(transport.calls()).extracting(FakeCall::method).containsExactly("GET", "GET");
        assertThat(transport.calls()).extracting(FakeCall::uri)
                .anySatisfy(uri -> assertThat(uri).contains("/sapi/v1/margin/transfer?asset=USDT&type=ROLL_OUT"))
                .anySatisfy(uri -> assertThat(uri).contains("/sapi/v1/margin/maxTransferable?asset=USDT&isolatedSymbol=BTCUSDT"));
    }

    @Test
    void reads_margin_account_and_risk_snapshots() {
        FakeTransport transport = new FakeTransport(
                new BinanceHttpResponse(200, """
                        {
                          "created": true,
                          "borrowEnabled": true,
                          "marginLevel": "11.64405625",
                          "collateralMarginLevel": "3.2",
                          "totalAssetOfBtc": "6.82728457",
                          "totalLiabilityOfBtc": "0.58633215",
                          "totalNetAssetOfBtc": "6.24095242",
                          "TotalCollateralValueInUSDT": "5.82728457",
                          "totalOpenOrderLossInUSDT": "582.728457",
                          "tradeEnabled": true,
                          "transferInEnabled": true,
                          "transferOutEnabled": true,
                          "accountType": "MARGIN_1",
                          "userAssets": [
                            {
                              "asset": "BTC",
                              "borrowed": "0.00000000",
                              "free": "0.00499500",
                              "interest": "0.00000000",
                              "locked": "0.00000000",
                              "netAsset": "0.00499500"
                            }
                          ]
                        }
                        """),
                new BinanceHttpResponse(200, """
                        {
                          "assets": [
                            {
                              "baseAsset": {
                                "asset": "BTC",
                                "borrowEnabled": true,
                                "borrowed": "0.00000000",
                                "free": "0.00000000",
                                "interest": "0.00000000",
                                "locked": "0.00000000",
                                "netAsset": "0.00000000",
                                "netAssetOfBtc": "0.00000000",
                                "repayEnabled": true,
                                "totalAsset": "0.00000000"
                              },
                              "quoteAsset": {
                                "asset": "USDT",
                                "borrowEnabled": true,
                                "borrowed": "0.00000000",
                                "free": "0.00000000",
                                "interest": "0.00000000",
                                "locked": "0.00000000",
                                "netAsset": "0.00000000",
                                "netAssetOfBtc": "0.00000000",
                                "repayEnabled": true,
                                "totalAsset": "0.00000000"
                              },
                              "symbol": "BTCUSDT",
                              "isolatedCreated": true,
                              "enabled": true,
                              "marginLevel": "0.00000000",
                              "marginLevelStatus": "EXCESSIVE",
                              "marginRatio": "0.00000000",
                              "indexPrice": "10000.00000000",
                              "liquidatePrice": "1000.00000000",
                              "liquidateRate": "1.00000000",
                              "tradeEnabled": true
                            }
                          ],
                          "totalAssetOfBtc": "0.00000000",
                          "totalLiabilityOfBtc": "0.00000000",
                          "totalNetAssetOfBtc": "0.00000000"
                        }
                        """),
                new BinanceHttpResponse(200, """
                        {
                          "enabledAccount": 5,
                          "maxAccount": 20
                        }
                        """),
                new BinanceHttpResponse(200, """
                        {
                          "normalBar": "1.5",
                          "marginCallBar": "1.3",
                          "forceLiquidationBar": "1.1"
                        }
                        """)
        );
        BinanceMarginAccountClient client = client(transport);

        BinanceCrossMarginAccountSnapshot crossAccount = client.crossAccount();
        BinanceIsolatedMarginAccountSnapshot isolatedAccount = client.isolatedAccount(
                new BinanceIsolatedMarginAccountQuery(List.of("BTCUSDT"))
        );
        BinanceIsolatedMarginAccountLimit isolatedLimit = client.isolatedAccountLimit();
        BinanceMarginTradeCoeff tradeCoeff = client.tradeCoeff();

        assertThat(crossAccount.accountType()).isEqualTo("MARGIN_1");
        assertThat(crossAccount.marginLevel()).isEqualByComparingTo("11.64405625");
        assertThat(crossAccount.totalCollateralValueInUsdt()).isEqualByComparingTo("5.82728457");
        assertThat(crossAccount.userAssets()).singleElement().satisfies(asset -> {
            assertThat(asset.asset()).isEqualTo("BTC");
            assertThat(asset.free()).isEqualByComparingTo("0.00499500");
        });
        assertThat(isolatedAccount.assets()).singleElement().satisfies(pair -> {
            assertThat(pair.symbol()).isEqualTo("BTCUSDT");
            assertThat(pair.marginLevelStatus()).isEqualTo("EXCESSIVE");
            assertThat(pair.baseAsset().asset()).isEqualTo("BTC");
            assertThat(pair.quoteAsset().asset()).isEqualTo("USDT");
        });
        assertThat(isolatedLimit.enabledAccount()).isEqualTo(5);
        assertThat(isolatedLimit.maxAccount()).isEqualTo(20);
        assertThat(tradeCoeff.normalBar()).isEqualByComparingTo("1.5");
        assertThat(tradeCoeff.marginCallBar()).isEqualByComparingTo("1.3");
        assertThat(tradeCoeff.forceLiquidationBar()).isEqualByComparingTo("1.1");
        assertThat(transport.calls()).extracting(FakeCall::method).containsExactly("GET", "GET", "GET", "GET");
        assertThat(transport.calls()).extracting(FakeCall::uri)
                .anySatisfy(uri -> assertThat(uri).contains("/sapi/v1/margin/account?"))
                .anySatisfy(uri -> assertThat(uri).contains("/sapi/v1/margin/isolated/account?symbols=BTCUSDT"))
                .anySatisfy(uri -> assertThat(uri).contains("/sapi/v1/margin/isolated/accountLimit?"))
                .anySatisfy(uri -> assertThat(uri).contains("/sapi/v1/margin/tradeCoeff?"));
    }

    @Test
    void reads_margin_special_keys() {
        FakeTransport transport = new FakeTransport(
                new BinanceHttpResponse(200, """
                        [
                          {
                            "apiName": "cross-special",
                            "apiKey": "special-key-1",
                            "ip": "192.168.0.1,192.168.0.2",
                            "type": "RSA",
                            "permissionMode": "TRADE"
                          }
                        ]
                        """),
                new BinanceHttpResponse(200, """
                        {
                          "apiName": "isolated-special",
                          "apiKey": "special-key-2",
                          "ip": "0.0.0.0,192.168.0.1",
                          "type": "Ed25519",
                          "permissionMode": "READ"
                        }
                        """)
        );
        BinanceMarginAccountClient client = client(transport);

        List<BinanceMarginSpecialKey> keys = client.specialKeys("BTCUSDT");
        BinanceMarginSpecialKey key = client.specialKey("special-key-2", "BTCUSDT");

        assertThat(keys).singleElement().satisfies(item -> {
            assertThat(item.apiName()).isEqualTo("cross-special");
            assertThat(item.apiKey()).isEqualTo("special-key-1");
            assertThat(item.type()).isEqualTo("RSA");
            assertThat(item.permissionMode()).isEqualTo("TRADE");
        });
        assertThat(key.apiName()).isEqualTo("isolated-special");
        assertThat(key.apiKey()).isEqualTo("special-key-2");
        assertThat(key.type()).isEqualTo("Ed25519");
        assertThat(key.permissionMode()).isEqualTo("READ");
        assertThat(transport.calls()).extracting(FakeCall::method).containsExactly("GET", "GET");
        assertThat(transport.calls()).extracting(FakeCall::uri)
                .anySatisfy(uri -> assertThat(uri).contains("/sapi/v1/margin/api-key-list?symbol=BTCUSDT"))
                .anySatisfy(uri -> assertThat(uri).contains("/sapi/v1/margin/apiKey?apiKey=special-key-2"));
    }

    @Test
    void converts_binance_error_response_to_api_exception() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(400, """
                {
                  "code": -3045,
                  "msg": "The system does not have enough asset now."
                }
                """));
        BinanceMarginAccountClient client = client(transport);

        assertThatThrownBy(() -> client.borrowRepay(
                new BinanceMarginBorrowRepayCommand("USDT", new BigDecimal("25.5"), "BORROW", false, null)
        ))
                .isInstanceOfSatisfying(BinanceApiException.class, exception -> {
                    assertThat(exception.httpStatusCode()).isEqualTo(400);
                    assertThat(exception.exchangeCode()).isEqualTo(-3045);
                    assertThat(exception.exchangeMessage()).contains("not have enough asset");
                });
    }

    private BinanceMarginAccountClient client(FakeTransport transport) {
        return new BinanceMarginAccountClient(
                marginBinance(),
                "api-key",
                "test-secret",
                FIXED_CLOCK,
                0,
                transport,
                JsonMapperFactory.create(),
                new BinanceRateLimitTracker(FIXED_CLOCK)
        );
    }

    private BinanceProperties marginBinance() {
        return new BinanceProperties(
                "MARGIN_CROSS",
                credentials(),
                rest(),
                websocket(),
                null,
                null,
                marginAccount(),
                null
        );
    }

    private BinanceProperties.Credentials credentials() {
        return new BinanceProperties.Credentials(
                "binance_real_main",
                "api-key",
                "api-secret",
                "HMAC_SHA256",
                List.of("USER_DATA", "MARGIN", "TRADE")
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
                "wss://stream.binance.com:9443",
                "/ws",
                "/ws",
                "/ws",
                "/ws",
                "/stream",
                24,
                null,
                20,
                null,
                60,
                null,
                5,
                1024,
                300,
                "MILLISECONDS",
                null,
                null
        );
    }

    private BinanceProperties.MarginAccount marginAccount() {
        return new BinanceProperties.MarginAccount(
                "/sapi/v1/margin/borrow-repay",
                "/sapi/v1/margin/transfer",
                "/sapi/v1/margin/maxTransferable",
                "/sapi/v1/margin/account",
                "/sapi/v1/margin/isolated/account",
                "/sapi/v1/margin/isolated/accountLimit",
                "/sapi/v1/margin/tradeCoeff",
                "/sapi/v1/margin/api-key-list",
                "/sapi/v1/margin/apiKey",
                List.of("BORROW", "REPAY"),
                List.of("ROLL_IN", "ROLL_OUT"),
                30,
                100,
                5
        );
    }

    private record FakeCall(String method, String uri, String apiKey, String apiKeyHeader) {
    }

    private static final class FakeTransport implements BinanceHttpTransport {
        private final List<BinanceHttpResponse> responses;
        private final List<FakeCall> calls = new ArrayList<>();

        FakeTransport(BinanceHttpResponse... responses) {
            this.responses = new ArrayList<>(List.of(responses));
        }

        @Override
        public BinanceHttpResponse sendPublic(URI uri, String method) {
            calls.add(new FakeCall(method, uri.toString(), null, null));
            return responses.removeFirst();
        }

        @Override
        public BinanceHttpResponse send(BinanceSignedRequest request,
                                        String method,
                                        String apiKey,
                                        String apiKeyHeader) {
            calls.add(new FakeCall(method, request.uri().toString(), apiKey, apiKeyHeader));
            return responses.removeFirst();
        }

        List<FakeCall> calls() {
            return List.copyOf(calls);
        }
    }
}
