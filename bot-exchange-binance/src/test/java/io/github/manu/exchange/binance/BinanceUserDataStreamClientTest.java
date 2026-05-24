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

class BinanceUserDataStreamClientTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-22T20:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void starts_renews_and_closes_listen_key_stream() {
        FakeTransport transport = new FakeTransport(
                new BinanceHttpResponse(200, "{\"listenKey\":\"stream-1\"}"),
                new BinanceHttpResponse(200, "{\"listenKey\":\"stream-1\"}"),
                new BinanceHttpResponse(200, "{}")
        );
        BinanceUserDataStreamClient client = client(transport);

        BinanceUserDataStreamSession started = client.start();
        BinanceUserDataStreamSession renewed = client.keepAlive(started.streamId());
        client.close(started.streamId());

        assertThat(started.mode()).isEqualTo("listen_key");
        assertThat(started.streamId()).isEqualTo("stream-1");
        assertThat(started.expiresAt()).isEqualTo(Instant.parse("2026-05-22T21:00:00Z"));
        assertThat(started.renewAfter()).isEqualTo(Instant.parse("2026-05-22T20:30:00Z"));
        assertThat(renewed.streamId()).isEqualTo("stream-1");
        assertThat(transport.calls()).extracting(FakeCall::method)
                .containsExactly("POST", "PUT", "DELETE");
        assertThat(transport.calls()).allSatisfy(call -> {
            assertThat(call.uri()).isEqualTo("https://demo-fapi.binance.com/fapi/v1/listenKey");
            assertThat(call.payload()).isEmpty();
            assertThat(call.signature()).isEmpty();
            assertThat(call.apiKey()).isEqualTo("api-key");
            assertThat(call.apiKeyHeader()).isEqualTo("X-MBX-APIKEY");
        });
    }

    @Test
    void throws_sanitized_binance_api_exception_for_exchange_error() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(400, """
                {"code": -1125, "msg": "This listenKey does not exist."}
                """));
        BinanceUserDataStreamClient client = client(transport);

        assertThatThrownBy(() -> client.start())
                .isInstanceOf(BinanceApiException.class)
                .hasMessageContaining("httpStatusCode=400")
                .hasMessageContaining("exchangeCode=-1125")
                .hasMessageContaining("listenKey");
    }

    @Test
    void rejects_missing_listen_key_response() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, "{}"));
        BinanceUserDataStreamClient client = client(transport);

        assertThatThrownBy(client::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing listenKey");
    }

    @Test
    void captures_rate_limit_headers_from_user_stream_responses() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, "{\"listenKey\":\"stream-1\"}", Map.of(
                "X-MBX-USED-WEIGHT-1M", List.of("5")
        )));
        BinanceUserDataStreamClient client = client(transport);

        client.start();

        assertThat(client.currentRateLimitUsage()).hasValueSatisfying(usage ->
                assertThat(usage.usedWeights()).containsEntry("X-MBX-USED-WEIGHT-1M", 5L));
    }

    private BinanceUserDataStreamClient client(FakeTransport transport) {
        return new BinanceUserDataStreamClient(
                binance(),
                "api-key",
                FIXED_CLOCK,
                transport,
                JsonMapperFactory.create()
        );
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

    private record FakeCall(String method, String uri, String payload, String signature, String apiKey, String apiKeyHeader) {
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
            calls.add(new FakeCall(
                    method,
                    request.uri().toString(),
                    request.payload(),
                    request.signature(),
                    apiKey,
                    apiKeyHeader
            ));
            return responses.removeFirst();
        }

        List<FakeCall> calls() {
            return List.copyOf(calls);
        }
    }
}
