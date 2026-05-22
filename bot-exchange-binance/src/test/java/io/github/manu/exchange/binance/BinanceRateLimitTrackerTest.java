package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceRateLimitTrackerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-22T20:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void captures_configured_weight_order_count_and_retry_after_headers() {
        BinanceRateLimitTracker tracker = new BinanceRateLimitTracker(FIXED_CLOCK);
        BinanceHttpResponse response = new BinanceHttpResponse(429, "{}", Map.of(
                "x-mbx-used-weight-1m", List.of("120"),
                "X-MBX-ORDER-COUNT-10S", List.of("4"),
                "Retry-After", List.of("2")
        ));

        tracker.observe(rest(), response);

        assertThat(tracker.current()).hasValueSatisfying(usage -> {
            assertThat(usage.observedAt()).isEqualTo(Instant.parse("2026-05-22T20:00:00Z"));
            assertThat(usage.usedWeights()).containsEntry("x-mbx-used-weight-1m", 120L);
            assertThat(usage.orderCounts()).containsEntry("X-MBX-ORDER-COUNT-10S", 4L);
            assertThat(usage.retryAfterSeconds()).isEqualTo(2L);
        });
    }

    @Test
    void ignores_unrelated_or_unparseable_headers() {
        BinanceRateLimitTracker tracker = new BinanceRateLimitTracker(FIXED_CLOCK);
        BinanceHttpResponse response = new BinanceHttpResponse(200, "{}", Map.of(
                "X-MBX-USED-WEIGHT-1M", List.of("not-a-number"),
                "X-OTHER", List.of("1")
        ));

        tracker.observe(rest(), response);

        assertThat(tracker.current()).isEmpty();
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
}
