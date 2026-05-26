package io.github.manu.reconciliation;

import io.github.manu.events.TradingEventType;

import java.util.List;
import java.util.Objects;

public record ReconciliationObservation(
        String provider,
        String environment,
        String account,
        String market,
        TradingEventType eventType,
        String entityKey,
        ReconciliationConfidenceStatus status,
        List<ReconciliationDifference> differences
) {

    public ReconciliationObservation {
        provider = requireText(provider, "provider");
        environment = requireText(environment, "environment");
        account = requireText(account, "account");
        market = requireText(market, "market");
        eventType = Objects.requireNonNull(eventType, "eventType");
        entityKey = requireText(entityKey, "entityKey");
        status = Objects.requireNonNull(status, "status");
        differences = differences == null ? List.of() : List.copyOf(differences);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
