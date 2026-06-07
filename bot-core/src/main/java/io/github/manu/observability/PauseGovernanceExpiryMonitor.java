package io.github.manu.observability;

import io.github.manu.audit.AuditLogger;
import io.github.manu.projection.TradingStateProjection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;

@Component
@ConditionalOnProperty(
        prefix = "trading.observability.pause-governance.expiry-monitor",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public final class PauseGovernanceExpiryMonitor {

    private static final int DEFAULT_MAX_EMITTED_EXPIRIES = 100_000;

    private final TradingStateProjection projection;
    private final PauseGovernanceMetrics metrics;
    private final AuditLogger auditLogger;
    private final Clock clock;
    private final int maxEmittedExpiries;
    private final LinkedHashSet<String> emittedExpiries = new LinkedHashSet<>();
    private final Object lock = new Object();

    @Autowired
    public PauseGovernanceExpiryMonitor(
            TradingStateProjection projection,
            PauseGovernanceMetrics metrics,
            AuditLogger auditLogger
    ) {
        this(projection, metrics, auditLogger, Clock.systemUTC(), DEFAULT_MAX_EMITTED_EXPIRIES);
    }

    PauseGovernanceExpiryMonitor(
            TradingStateProjection projection,
            PauseGovernanceMetrics metrics,
            AuditLogger auditLogger,
            Clock clock,
            int maxEmittedExpiries
    ) {
        if (maxEmittedExpiries <= 0) {
            throw new IllegalArgumentException("maxEmittedExpiries must be positive");
        }
        this.projection = Objects.requireNonNull(projection, "projection");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxEmittedExpiries = maxEmittedExpiries;
    }

    @Scheduled(fixedDelayString = "${trading.observability.pause-governance.expiry-monitor.interval-millis:30000}")
    public void scheduledScan() {
        scan();
    }

    public ExpiryScanResult scan() {
        Instant now = Instant.now(clock);
        int emittedTransitions = 0;
        for (TradingStateProjection.PauseGovernanceState state : projection.snapshot().pauseGovernance()) {
            if (expiredActivePause(state, now) && markEmitted(state)) {
                metrics.pauseExpired(state);
                auditLogger.pauseGovernanceExpired(state, now);
                emittedTransitions++;
            }
        }
        return new ExpiryScanResult(emittedTransitions);
    }

    private boolean expiredActivePause(TradingStateProjection.PauseGovernanceState state, Instant now) {
        return Boolean.TRUE.equals(state.active()) && state.expiresAt() != null && state.expired(now);
    }

    private boolean markEmitted(TradingStateProjection.PauseGovernanceState state) {
        synchronized (lock) {
            boolean added = emittedExpiries.add(emittedKey(state));
            while (emittedExpiries.size() > maxEmittedExpiries) {
                emittedExpiries.remove(emittedExpiries.iterator().next());
            }
            return added;
        }
    }

    private String emittedKey(TradingStateProjection.PauseGovernanceState state) {
        return String.join(
                "|",
                text(state.provider()),
                text(state.environment()),
                text(state.account()),
                text(state.market()),
                text(state.pauseScope()),
                text(state.pauseTarget()),
                text(state.remediationId()),
                text(state.eventId()),
                text(state.expiresAt())
        );
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    public record ExpiryScanResult(int emittedTransitions) {
    }
}
