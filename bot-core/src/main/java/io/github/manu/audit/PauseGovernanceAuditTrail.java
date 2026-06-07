package io.github.manu.audit;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

@Component
public final class PauseGovernanceAuditTrail {

    private static final Logger log = LoggerFactory.getLogger(PauseGovernanceAuditTrail.class);
    private static final int DEFAULT_MAX_EVENTS = 1_000;

    private final int maxEvents;
    private final List<PauseGovernanceAuditStore> stores;
    private final PauseGovernanceAuditMetrics auditMetrics;
    private final Deque<PauseGovernanceAuditEvent> events = new ArrayDeque<>();

    public PauseGovernanceAuditTrail() {
        this(DEFAULT_MAX_EVENTS, List.of(), new PauseGovernanceAuditMetrics());
    }

    @Autowired
    public PauseGovernanceAuditTrail(
            List<PauseGovernanceAuditStore> stores,
            PauseGovernanceAuditMetrics auditMetrics
    ) {
        this(DEFAULT_MAX_EVENTS, stores, auditMetrics);
    }

    PauseGovernanceAuditTrail(int maxEvents) {
        this(maxEvents, List.of(), new PauseGovernanceAuditMetrics());
    }

    PauseGovernanceAuditTrail(int maxEvents, List<PauseGovernanceAuditStore> stores) {
        this(maxEvents, stores, new PauseGovernanceAuditMetrics());
    }

    PauseGovernanceAuditTrail(
            int maxEvents,
            List<PauseGovernanceAuditStore> stores,
            PauseGovernanceAuditMetrics auditMetrics
    ) {
        if (maxEvents <= 0) {
            throw new IllegalArgumentException("maxEvents must be positive");
        }
        this.maxEvents = maxEvents;
        this.stores = stores == null ? List.of() : List.copyOf(stores);
        this.auditMetrics = Objects.requireNonNull(auditMetrics, "auditMetrics");
    }

    public synchronized void record(PauseGovernanceAuditEvent event) {
        PauseGovernanceAuditEvent normalizedEvent = Objects.requireNonNull(event, "event");
        events.addLast(normalizedEvent);
        while (events.size() > maxEvents) {
            events.removeFirst();
        }
        for (PauseGovernanceAuditStore store : stores) {
            try {
                store.record(normalizedEvent);
            } catch (PauseGovernanceAuditStoreException exception) {
                auditMetrics.auditStoreFailure("record", store.storeName());
                log.warn("failed to persist pause governance audit event", exception);
            }
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
        if (!stores.isEmpty()) {
            try {
                return stores.getFirst().recent(provider, environment, account, market, limit);
            } catch (PauseGovernanceAuditStoreException exception) {
                auditMetrics.auditStoreFailure("query", stores.getFirst().storeName());
                log.warn("failed to query persisted pause governance audit events", exception);
            }
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
