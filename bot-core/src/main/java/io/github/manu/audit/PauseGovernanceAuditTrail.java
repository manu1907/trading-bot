package io.github.manu.audit;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

@Component
public final class PauseGovernanceAuditTrail {

    private static final int DEFAULT_MAX_EVENTS = 1_000;

    private final int maxEvents;
    private final Deque<PauseGovernanceAuditEvent> events = new ArrayDeque<>();

    public PauseGovernanceAuditTrail() {
        this(DEFAULT_MAX_EVENTS);
    }

    PauseGovernanceAuditTrail(int maxEvents) {
        if (maxEvents <= 0) {
            throw new IllegalArgumentException("maxEvents must be positive");
        }
        this.maxEvents = maxEvents;
    }

    public synchronized void record(PauseGovernanceAuditEvent event) {
        events.addLast(Objects.requireNonNull(event, "event"));
        while (events.size() > maxEvents) {
            events.removeFirst();
        }
    }

    public synchronized List<PauseGovernanceAuditEvent> recent(
            String provider,
            String environment,
            String account,
            String market,
            int limit
    ) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<PauseGovernanceAuditEvent> matches = new ArrayList<>();
        events.descendingIterator()
                .forEachRemaining(event -> {
                    if (matches.size() < limit
                            && Objects.equals(event.provider(), provider)
                            && Objects.equals(event.environment(), environment)
                            && Objects.equals(event.account(), account)
                            && Objects.equals(event.market(), market)) {
                        matches.add(event);
                    }
                });
        return List.copyOf(matches);
    }

    public record PauseGovernanceAuditEvent(
            String eventType,
            String provider,
            String environment,
            String account,
            String market,
            String symbol,
            String pauseScope,
            String pauseTarget,
            String remediationId,
            String eventId,
            String sourcePauseRemediationId,
            String commandId,
            String clientOrderId,
            String decisionId,
            String riskDecision,
            String outcome,
            String actor,
            String reason,
            String expiresAt,
            String invalidReason,
            Instant occurredAt
    ) {
    }
}
