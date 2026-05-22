package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceServerTimeSynchronizerTest {

    @Test
    void syncs_server_time_using_local_request_midpoint() throws Exception {
        StepClock clock = new StepClock(
                Instant.ofEpochMilli(1_000L),
                Instant.ofEpochMilli(1_100L)
        );
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, """
                {"serverTime": 1350}
                """));
        BinanceServerTimeSynchronizer synchronizer = synchronizer(transport, clock);

        BinanceServerTimeSnapshot snapshot = synchronizer.sync();

        assertThat(transport.publicCalls).containsExactly(new PublicCall(
                "GET",
                "https://demo-fapi.binance.com/fapi/v1/time"
        ));
        assertThat(snapshot.serverTime()).isEqualTo(Instant.ofEpochMilli(1_350L));
        assertThat(snapshot.localRequestStartedAt()).isEqualTo(Instant.ofEpochMilli(1_000L));
        assertThat(snapshot.localResponseReceivedAt()).isEqualTo(Instant.ofEpochMilli(1_100L));
        assertThat(snapshot.roundTripMillis()).isEqualTo(100L);
        assertThat(snapshot.offsetMillis()).isEqualTo(300L);
    }

    @Test
    void throws_sanitized_exception_when_server_time_endpoint_fails() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(418, """
                {"code": -1003, "msg": "Too many requests."}
                """));
        BinanceServerTimeSynchronizer synchronizer = synchronizer(
                transport,
                new StepClock(Instant.ofEpochMilli(1_000L), Instant.ofEpochMilli(1_010L))
        );

        assertThatThrownBy(synchronizer::sync)
                .isInstanceOf(BinanceApiException.class)
                .hasMessageContaining("httpStatusCode=418")
                .hasMessageContaining("server time sync failed");
    }

    @Test
    void rejects_missing_server_time_response_field() {
        FakeTransport transport = new FakeTransport(new BinanceHttpResponse(200, "{}"));
        BinanceServerTimeSynchronizer synchronizer = synchronizer(
                transport,
                new StepClock(Instant.ofEpochMilli(1_000L), Instant.ofEpochMilli(1_010L))
        );

        assertThatThrownBy(synchronizer::sync)
                .isInstanceOf(RuntimeException.class);
    }

    private BinanceServerTimeSynchronizer synchronizer(FakeTransport transport, Clock clock) {
        return new BinanceServerTimeSynchronizer(rest(), transport, JsonMapperFactory.create(), clock);
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

    private record PublicCall(String method, String uri) {
    }

    private static final class FakeTransport implements BinanceHttpTransport {

        private final Queue<BinanceHttpResponse> responses;
        private final List<PublicCall> publicCalls = new java.util.ArrayList<>();

        private FakeTransport(BinanceHttpResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public BinanceHttpResponse sendPublic(URI uri, String method) {
            publicCalls.add(new PublicCall(method, uri.toString()));
            return responses.remove();
        }

        @Override
        public BinanceHttpResponse send(BinanceSignedRequest request,
                                        String method,
                                        String apiKey,
                                        String apiKeyHeader) {
            throw new UnsupportedOperationException("signed requests are not used by this test");
        }
    }

    private static final class StepClock extends Clock {

        private final ZoneId zone;
        private final Queue<Instant> instants;

        private StepClock(Instant... instants) {
            this(ZoneId.of("UTC"), new ArrayDeque<>(List.of(instants)));
        }

        private StepClock(ZoneId zone, Queue<Instant> instants) {
            this.zone = zone;
            this.instants = instants;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new StepClock(zone, new ArrayDeque<>(instants));
        }

        @Override
        public Instant instant() {
            return instants.remove();
        }
    }
}
