package io.github.manu.observability;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.messaging.TradingEventHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Component
public final class PauseGovernanceDecisionMetricsHandler implements TradingEventHandler {

    private final PauseGovernanceMetrics metrics;

    @Autowired
    public PauseGovernanceDecisionMetricsHandler(PauseGovernanceMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public CompletableFuture<Void> handle(TradingEventEnvelope<?> envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.eventType() != TradingEventType.REMEDIATION_DECISION) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Expected REMEDIATION_DECISION envelope"));
        }
        RemediationDecisionEvent event = (RemediationDecisionEvent) envelope.value();
        String pauseScope = pauseScope(event);
        if (pauseScope != null) {
            metrics.pauseActivationDecision(event, pauseScope, validExpiryConfigured(event));
        }
        return CompletableFuture.completedFuture(null);
    }

    private String pauseScope(RemediationDecisionEvent event) {
        return switch (value(event.getAction())) {
            case "PAUSE_ACCOUNT" -> "ACCOUNT";
            case "PAUSE_SYMBOL" -> "SYMBOL";
            default -> null;
        };
    }

    private boolean validExpiryConfigured(RemediationDecisionEvent event) {
        if (event.getAttributes() == null) {
            return false;
        }
        String expiresAt = value(event.getAttributes().get("pause_expires_at"));
        if (expiresAt == null) {
            return false;
        }
        try {
            Instant.parse(expiresAt);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String value(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }
}
