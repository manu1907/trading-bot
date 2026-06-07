package io.github.manu.observability;

import io.github.manu.audit.AuditLogger;
import io.github.manu.audit.PauseGovernanceAuditTrail;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PauseGovernanceExpiryMonitorTest {

    private static final Instant NOW = Instant.parse("2026-06-07T14:30:00Z");

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TradingStateProjection projection = new TradingStateProjection();
    private final PauseGovernanceAuditTrail auditTrail = new PauseGovernanceAuditTrail();
    private final PauseGovernanceExpiryMonitor monitor = new PauseGovernanceExpiryMonitor(
            projection,
            new PauseGovernanceMetrics(meterRegistry),
            new AuditLogger(auditTrail),
            Clock.fixed(NOW, ZoneOffset.UTC),
            100
    );

    @Test
    void emits_metric_and_audit_once_for_new_expired_active_pause() {
        projection.restore(snapshot(List.of(
                pause("SYMBOL", "BTCUSDT", "BTCUSDT", true, Map.of(
                        "pause_expires_at",
                        NOW.minusSeconds(1).toString()
                )),
                pause("ACCOUNT", "main", null, true, Map.of(
                        "pause_expires_at",
                        NOW.plusSeconds(60).toString()
                )),
                pause("SYMBOL", "ETHUSDT", "ETHUSDT", false, Map.of(
                        "pause_expires_at",
                        NOW.minusSeconds(1).toString()
                )),
                pause("SYMBOL", "BNBUSDT", "BNBUSDT", true, Map.of(
                        "pause_expires_at",
                        "not-an-instant"
                ))
        )));

        assertThat(monitor.scan().emittedTransitions()).isEqualTo(1);

        assertThat(counter("SYMBOL")).isEqualTo(1.0d);
        assertThat(meterRegistry.find("trading.pause_governance.expiry.transitions")
                .tag("scope", "ACCOUNT")
                .counter()).isNull();

        List<PauseGovernanceAuditTrail.PauseGovernanceAuditEvent> events = auditTrail.recent(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                10
        );
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().eventType()).isEqualTo("pause_governance_expired");
        assertThat(events.getFirst().pauseScope()).isEqualTo("SYMBOL");
        assertThat(events.getFirst().pauseTarget()).isEqualTo("BTCUSDT");
        assertThat(events.getFirst().outcome()).isEqualTo("expired");
        assertThat(events.getFirst().actor()).isEqualTo("pause_governance_expiry_monitor");
        assertThat(events.getFirst().expiresAt()).isEqualTo(NOW.minusSeconds(1).toString());
        assertThat(events.getFirst().occurredAt()).isEqualTo(NOW);

        assertThat(monitor.scan().emittedTransitions()).isZero();
        assertThat(counter("SYMBOL")).isEqualTo(1.0d);
        assertThat(auditTrail.recent("binance", "demo", "main", "usd_m_futures", 10)).hasSize(1);
    }

    private double counter(String scope) {
        return meterRegistry.get("trading.pause_governance.expiry.transitions")
                .tag("provider", "binance")
                .tag("environment", "demo")
                .tag("account", "main")
                .tag("market", "usd_m_futures")
                .tag("scope", scope)
                .counter()
                .count();
    }

    private TradingStateSnapshot snapshot(List<TradingStateProjection.PauseGovernanceState> pauses) {
        return new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                pauses,
                List.of()
        );
    }

    private TradingStateProjection.PauseGovernanceState pause(
            String pauseScope,
            String pauseTarget,
            String symbol,
            boolean active,
            Map<String, String> attributes
    ) {
        return new TradingStateProjection.PauseGovernanceState(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                pauseScope,
                pauseTarget,
                symbol,
                "remediation-" + pauseTarget,
                "POSITION",
                active ? "PAUSE_" + pauseScope : "RELEASE_" + pauseScope + "_PAUSE",
                "external_position_change",
                List.of("intervention:external_position_change"),
                "automated_remediation_policy",
                "policy selected pause governance",
                attributes,
                active,
                NOW.minusSeconds(60),
                "evt-" + pauseTarget
        );
    }
}
