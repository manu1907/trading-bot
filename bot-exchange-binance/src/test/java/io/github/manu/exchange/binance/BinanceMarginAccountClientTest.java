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
                List.of("BORROW", "REPAY"),
                List.of("ROLL_IN", "ROLL_OUT"),
                30,
                100
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
