package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

final class BinanceRateLimitTracker {

    private static final String RETRY_AFTER = "RETRY-AFTER";

    private final Clock clock;
    private final AtomicReference<BinanceRateLimitUsage> currentUsage = new AtomicReference<>();

    BinanceRateLimitTracker(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Optional<BinanceRateLimitUsage> current() {
        return Optional.ofNullable(currentUsage.get());
    }

    void observe(BinanceProperties.Rest rest, BinanceHttpResponse response) {
        Map<String, Long> usedWeights = matchingHeaders(rest.weightHeaders(), response.headers());
        Map<String, Long> orderCounts = matchingHeaders(rest.orderCountHeaders(), response.headers());
        Long retryAfterSeconds = headerLong(response.headers(), RETRY_AFTER);

        if (usedWeights.isEmpty() && orderCounts.isEmpty() && retryAfterSeconds == null) {
            return;
        }

        currentUsage.set(new BinanceRateLimitUsage(
                clock.instant(),
                usedWeights,
                orderCounts,
                retryAfterSeconds
        ));
    }

    private Map<String, Long> matchingHeaders(List<String> configuredPrefixes, Map<String, List<String>> headers) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (matchesAnyPrefix(entry.getKey(), configuredPrefixes)) {
                parseLastLong(entry.getValue()).ifPresent(value -> values.put(entry.getKey(), value));
            }
        }
        return values;
    }

    private boolean matchesAnyPrefix(String headerName, List<String> configuredPrefixes) {
        String normalizedHeader = normalize(headerName);
        for (String configuredPrefix : configuredPrefixes) {
            String normalizedPrefix = normalize(configuredPrefix);
            if (normalizedHeader.equals(normalizedPrefix) || normalizedHeader.startsWith(normalizedPrefix + "-")) {
                return true;
            }
        }
        return false;
    }

    private Long headerLong(Map<String, List<String>> headers, String headerName) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (normalize(headerName).equals(normalize(entry.getKey()))) {
                return parseLastLong(entry.getValue()).orElse(null);
            }
        }
        return null;
    }

    private Optional<Long> parseLastLong(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(Long.parseLong(values.getLast()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
