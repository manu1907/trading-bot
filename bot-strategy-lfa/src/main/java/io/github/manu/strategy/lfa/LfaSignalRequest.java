package io.github.manu.strategy.lfa;

import java.math.BigDecimal;
import java.util.Objects;

public record LfaSignalRequest(
        String strategyId,
        String provider,
        String environment,
        String account,
        String market,
        BigDecimal minImbalanceRatio,
        BigDecimal maxSpreadBps,
        BigDecimal minTopOfBookQuoteNotional,
        long maxMarketDataAgeMillis,
        String targetQuantity,
        String targetNotional
) {

    public LfaSignalRequest {
        strategyId = required(strategyId, "strategyId");
        provider = required(provider, "provider");
        environment = required(environment, "environment");
        account = required(account, "account");
        market = required(market, "market");
        minImbalanceRatio = positive(minImbalanceRatio, "minImbalanceRatio");
        if (minImbalanceRatio.compareTo(BigDecimal.ONE) <= 0) {
            throw new IllegalArgumentException("minImbalanceRatio must be greater than 1");
        }
        maxSpreadBps = positive(maxSpreadBps, "maxSpreadBps");
        minTopOfBookQuoteNotional = positive(minTopOfBookQuoteNotional, "minTopOfBookQuoteNotional");
        if (maxMarketDataAgeMillis <= 0) {
            throw new IllegalArgumentException("maxMarketDataAgeMillis must be positive");
        }
        if ((targetQuantity == null || targetQuantity.isBlank()) && (targetNotional == null || targetNotional.isBlank())) {
            throw new IllegalArgumentException("targetQuantity or targetNotional is required");
        }
        targetQuantity = blankToNull(targetQuantity);
        targetNotional = blankToNull(targetNotional);
    }

    public static LfaSignalRequest conservativeUsdMFutures(
            String strategyId,
            String provider,
            String environment,
            String account,
            String market,
            String targetQuantity
    ) {
        return new LfaSignalRequest(
                strategyId,
                provider,
                environment,
                account,
                market,
                new BigDecimal("1.50"),
                new BigDecimal("5"),
                new BigDecimal("250"),
                30_000,
                targetQuantity,
                null
        );
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static BigDecimal positive(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
