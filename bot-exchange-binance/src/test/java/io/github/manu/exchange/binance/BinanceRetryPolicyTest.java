package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceRetryPolicyTest {

    private final BinanceRetryPolicy retryPolicy = new BinanceRetryPolicy(rest());

    @Test
    void treats_configured_status_codes_as_retryable() {
        BinanceRetryPolicy.RetryDecision decision = retryPolicy.decide(429, "Too many requests");

        assertThat(decision.retryable()).isTrue();
        assertThat(decision.reconcileBeforeRetry()).isFalse();
        assertThat(decision.backoffMillis()).isEqualTo(200);
    }

    @Test
    void requires_reconciliation_for_unknown_execution_status_codes() {
        BinanceRetryPolicy.RetryDecision decision = retryPolicy.decide(503, "Service Unavailable");

        assertThat(decision.retryable()).isTrue();
        assertThat(decision.reconcileBeforeRetry()).isTrue();
    }

    @Test
    void requires_reconciliation_for_unknown_execution_messages() {
        BinanceRetryPolicy.RetryDecision decision = retryPolicy.decide(
                500,
                "Unknown error, please check your request or try again later."
        );

        assertThat(decision.retryable()).isTrue();
        assertThat(decision.reconcileBeforeRetry()).isTrue();
    }

    @Test
    void rejects_unconfigured_non_retryable_status_codes() {
        BinanceRetryPolicy.RetryDecision decision = retryPolicy.decide(400, "Bad request");

        assertThat(decision.retryable()).isFalse();
        assertThat(decision.reconcileBeforeRetry()).isFalse();
    }

    @Test
    void stops_after_configured_retry_budget() {
        assertThat(retryPolicy.canAttemptRetry(0)).isTrue();
        assertThat(retryPolicy.canAttemptRetry(2)).isTrue();
        assertThat(retryPolicy.canAttemptRetry(3)).isFalse();
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
