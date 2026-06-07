package io.github.manu.observability;

import io.github.manu.audit.AuditLogger;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.messaging.TradingEventHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Component
public final class PauseGovernanceDecisionAuditHandler implements TradingEventHandler {

    private final AuditLogger auditLogger;

    @Autowired
    public PauseGovernanceDecisionAuditHandler(AuditLogger auditLogger) {
        this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger");
    }

    @Override
    public CompletableFuture<Void> handle(TradingEventEnvelope<?> envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.eventType() != TradingEventType.REMEDIATION_DECISION) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Expected REMEDIATION_DECISION envelope"));
        }
        RemediationDecisionEvent event = (RemediationDecisionEvent) envelope.value();
        String pauseScope = pauseScope(event);
        String pauseTarget = pauseTarget(event, pauseScope);
        if (pauseScope != null && pauseTarget != null) {
            auditLogger.pauseGovernanceActivated(event, pauseScope, pauseTarget);
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

    private String pauseTarget(RemediationDecisionEvent event, String pauseScope) {
        return switch (pauseScope == null ? "" : pauseScope) {
            case "ACCOUNT" -> value(event.getAccount());
            case "SYMBOL" -> value(event.getSymbol());
            default -> null;
        };
    }

    private String value(CharSequence value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }
}
