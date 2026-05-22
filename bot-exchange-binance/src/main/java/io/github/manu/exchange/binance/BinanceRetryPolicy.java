package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;

import java.util.Locale;

final class BinanceRetryPolicy {

    private final BinanceProperties.Rest rest;

    BinanceRetryPolicy(BinanceProperties.Rest rest) {
        this.rest = rest;
    }

    boolean canAttemptRetry(int previousFailures) {
        return previousFailures < rest.maxRetries();
    }

    RetryDecision decide(int statusCode, String responseMessage) {
        boolean reconcileBeforeRetry = requiresReconciliation(statusCode, responseMessage);
        boolean retryable = reconcileBeforeRetry || rest.retryOnStatusCodes().contains(statusCode);
        return new RetryDecision(retryable, reconcileBeforeRetry, rest.retryBackoffMillis());
    }

    private boolean requiresReconciliation(int statusCode, String responseMessage) {
        BinanceProperties.UnknownExecutionStatus unknown = rest.unknownExecutionStatus();
        if (unknown.reconcileBeforeRetryStatusCodes().contains(statusCode)) {
            return true;
        }
        if (responseMessage == null || responseMessage.isBlank()) {
            return false;
        }

        String normalizedMessage = responseMessage.toLowerCase(Locale.ROOT);
        return unknown.retryableMessages().stream()
                .map(message -> message.toLowerCase(Locale.ROOT))
                .anyMatch(normalizedMessage::contains);
    }

    record RetryDecision(boolean retryable, boolean reconcileBeforeRetry, int backoffMillis) {
    }
}
