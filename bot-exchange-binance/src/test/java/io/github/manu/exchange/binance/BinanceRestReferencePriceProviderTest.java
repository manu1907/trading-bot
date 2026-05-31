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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceRestReferencePriceProviderTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-31T12:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void fetches_spot_average_price_from_configured_public_endpoint() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                {"mins": 5, "price": "68000.12345678", "closeTime": 1694061154503}
                """));
        BinanceRestReferencePriceProvider provider = provider(transport, "/api/v3/avgPrice", "price");

        Optional<BigDecimal> price = provider.weightedAveragePrice("BTCUSDT");

        assertThat(price).hasValue(new BigDecimal("68000.12345678"));
        assertThat(transport.calls()).containsExactly(new PublicCall(
                "GET",
                "https://api.binance.test/api/v3/avgPrice?symbol=BTCUSDT"
        ));
    }

    @Test
    void fetches_futures_weighted_average_price_from_array_response() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                [
                  {"symbol": "ETHUSDT", "weightedAvgPrice": "3500.00000000"},
                  {"symbol": "BTCUSDT", "weightedAvgPrice": "68100.00000000"}
                ]
                """));
        BinanceRestReferencePriceProvider provider = provider(transport, "/fapi/v1/ticker/24hr", "weightedAvgPrice");

        Optional<BigDecimal> price = provider.weightedAveragePrice("BTCUSDT");

        assertThat(price).hasValue(new BigDecimal("68100.00000000"));
    }

    @Test
    void throws_sanitized_exception_for_reference_price_http_failure() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(503, """
                {"code": -1008, "msg": "Request throttled by system-level protection."}
                """));
        BinanceRestReferencePriceProvider provider = provider(transport, "/fapi/v1/ticker/24hr", "weightedAvgPrice");

        assertThatThrownBy(() -> provider.weightedAveragePrice("BTCUSDT"))
                .isInstanceOf(BinanceApiException.class)
                .hasMessageContaining("httpStatusCode=503")
                .hasMessageContaining("reference price fetch failed");
    }

    private BinanceRestReferencePriceProvider provider(
            FakeTransport transport,
            String referencePricePath,
            String referencePriceResponseField
    ) {
        return new BinanceRestReferencePriceProvider(
                binance(referencePricePath, referencePriceResponseField),
                transport,
                JsonMapperFactory.create(),
                FIXED_CLOCK
        );
    }

    private BinanceProperties binance(String referencePricePath, String referencePriceResponseField) {
        return new BinanceProperties(
                "FUTURES_USD_M",
                new BinanceProperties.Credentials(
                        "binance_demo_main",
                        "api-key",
                        "api-secret",
                        "HMAC_SHA256",
                        List.of("USER_DATA", "TRADE")
                ),
                rest(),
                websocket(),
                trading(referencePricePath, referencePriceResponseField),
                null,
                null,
                null
        );
    }

    private BinanceProperties.Rest rest() {
        return new BinanceProperties.Rest(
                "https://api.binance.test",
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
                new BinanceProperties.UnknownExecutionStatus(List.of(), List.of())
        );
    }

    private BinanceProperties.Websocket websocket() {
        return new BinanceProperties.Websocket(
                "wss://example.invalid",
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
                "wss://example.invalid",
                "/ws-api/v3"
        );
    }

    private BinanceProperties.Trading trading(String referencePricePath, String referencePriceResponseField) {
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
                null,
                referencePricePath,
                referencePriceResponseField,
                List.of("BUY", "SELL"),
                List.of("LIMIT", "MARKET"),
                List.of("GTC", "IOC", "FOK"),
                List.of("ACK", "RESULT"),
                List.of("EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH"),
                List.of("BOTH", "LONG", "SHORT"),
                List.of("LIMIT"),
                List.of(),
                List.of(),
                List.of(),
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
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                true
        );
    }

    private record PublicCall(String method, String uri) {
    }

    private static final class FakeTransport implements BinanceHttpTransport {
        private final List<BinanceHttpResponse> responses;
        private final List<PublicCall> calls = new ArrayList<>();

        FakeTransport(BinanceHttpResponse... responses) {
            this.responses = new ArrayList<>(List.of(responses));
        }

        @Override
        public BinanceHttpResponse sendPublic(URI uri, String method) {
            calls.add(new PublicCall(method, uri.toString()));
            return responses.removeFirst();
        }

        @Override
        public BinanceHttpResponse send(BinanceSignedRequest request,
                                        String method,
                                        String apiKey,
                                        String apiKeyHeader) {
            throw new UnsupportedOperationException("signed requests are not used by this test");
        }

        List<PublicCall> calls() {
            return List.copyOf(calls);
        }
    }
}
