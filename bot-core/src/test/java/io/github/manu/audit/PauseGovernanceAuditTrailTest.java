package io.github.manu.audit;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.events.v1.OrderCommandAction;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.events.v1.RiskDecision;
import io.github.manu.events.v1.RiskDecisionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PauseGovernanceAuditTrailTest {

    private static final Instant NOW = Instant.parse("2026-06-07T14:30:00Z");

    @TempDir
    private Path temporaryDirectory;

    private final PauseGovernanceAuditTrail auditTrail = new PauseGovernanceAuditTrail();
    private final AuditLogger auditLogger = new AuditLogger(auditTrail);

    @Test
    void audit_logger_records_pause_release_events_for_query() {
        auditLogger.pauseGovernanceReleased(
                remediationDecision(),
                "SYMBOL",
                "BTCUSDT",
                "remediation-pause-1"
        );

        assertThat(auditTrail.recent("binance", "demo", "main", "usd_m_futures", 10))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo("pause_governance_released");
                    assertThat(event.pauseScope()).isEqualTo("SYMBOL");
                    assertThat(event.pauseTarget()).isEqualTo("BTCUSDT");
                    assertThat(event.remediationId()).isEqualTo("pause-release-1");
                    assertThat(event.sourcePauseRemediationId()).isEqualTo("remediation-pause-1");
                    assertThat(event.outcome()).isEqualTo("released");
                    assertThat(event.actor()).isEqualTo("operator");
                    assertThat(event.reason()).isEqualTo("risk cleared");
                    assertThat(event.occurredAt()).isEqualTo(NOW);
                });
    }

    @Test
    void audit_logger_records_pause_override_events_for_query() {
        auditLogger.pauseOverrideEvaluated(orderCommand(), riskDecision());

        assertThat(auditTrail.recent("binance", "demo", "main", "usd_m_futures", 10))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo("pause_override_evaluated");
                    assertThat(event.commandId()).isEqualTo("cmd-1");
                    assertThat(event.clientOrderId()).isEqualTo("client-1");
                    assertThat(event.decisionId()).isEqualTo("risk-decision:cmd-1");
                    assertThat(event.riskDecision()).isEqualTo("APPROVED");
                    assertThat(event.outcome()).isEqualTo("allowed");
                    assertThat(event.actor()).isEqualTo("operator");
                    assertThat(event.reason()).isEqualTo("controlled test order");
                    assertThat(event.expiresAt()).isEqualTo(NOW.plusSeconds(60).toString());
                    assertThat(event.occurredAt()).isEqualTo(NOW);
                });
    }

    @Test
    void query_returns_recent_matching_events_first_and_applies_limit() {
        auditTrail.record(event("old", "binance", "demo", "main", "usd_m_futures"));
        auditTrail.record(event("ignored", "binance", "demo", "other", "usd_m_futures"));
        auditTrail.record(event("new", "binance", "demo", "main", "usd_m_futures"));

        assertThat(auditTrail.recent("binance", "demo", "main", "usd_m_futures", 1))
                .singleElement()
                .extracting(PauseGovernanceAuditTrail.PauseGovernanceAuditEvent::eventId)
                .isEqualTo("new");
    }

    @Test
    void query_uses_configured_store_after_restart() {
        FilePauseGovernanceAuditStore store = new FilePauseGovernanceAuditStore(
                temporaryDirectory.resolve("pause-governance.jsonl"),
                JsonMapperFactory.create()
        );
        PauseGovernanceAuditTrail persistedTrail = new PauseGovernanceAuditTrail(1, List.of(store));
        persistedTrail.record(event("persisted", "binance", "demo", "main", "usd_m_futures"));

        PauseGovernanceAuditTrail restartedTrail = new PauseGovernanceAuditTrail(1, List.of(store));

        assertThat(restartedTrail.recent("binance", "demo", "main", "usd_m_futures", 10))
                .singleElement()
                .extracting(PauseGovernanceAuditTrail.PauseGovernanceAuditEvent::eventId)
                .isEqualTo("persisted");
    }

    private PauseGovernanceAuditTrail.PauseGovernanceAuditEvent event(
            String eventId,
            String provider,
            String environment,
            String account,
            String market
    ) {
        return new PauseGovernanceAuditTrail.PauseGovernanceAuditEvent(
                "pause_governance_released",
                provider,
                environment,
                account,
                market,
                "BTCUSDT",
                "SYMBOL",
                "BTCUSDT",
                "remediation-" + eventId,
                eventId,
                null,
                null,
                null,
                null,
                null,
                "released",
                "operator",
                "risk cleared",
                null,
                null,
                NOW
        );
    }

    private RemediationDecisionEvent remediationDecision() {
        return RemediationDecisionEvent.newBuilder()
                .setEventId("evt-release-1")
                .setSchemaVersion(1)
                .setRemediationId("pause-release-1")
                .setProvider("binance")
                .setEnvironment("demo")
                .setAccount("main")
                .setMarket("usd_m_futures")
                .setSymbol("BTCUSDT")
                .setScope("PAUSE_GOVERNANCE")
                .setAction("RELEASE_SYMBOL_PAUSE")
                .setClientOrderId(null)
                .setPositionSide(null)
                .setInterventionReason("external_position_change")
                .setReasons(List.of("pause_governance:release"))
                .setDecidedBy("operator")
                .setDecisionReason("risk cleared")
                .setDecidedAtMicros(NOW)
                .setAttributes(Map.of())
                .build();
    }

    private OrderCommandEvent orderCommand() {
        return OrderCommandEvent.newBuilder()
                .setEventId("evt-command-1")
                .setSchemaVersion(1)
                .setCommandId("cmd-1")
                .setStrategyId("lfa")
                .setProvider("binance")
                .setEnvironment("demo")
                .setAccount("main")
                .setMarket("usd_m_futures")
                .setSymbol("BTCUSDT")
                .setAction(OrderCommandAction.NEW)
                .setSide(OrderCommandSide.BUY)
                .setOrderType(OrderCommandType.LIMIT)
                .setQuantity("0.001")
                .setPrice("50000")
                .setReduceOnly(false)
                .setClosePosition(false)
                .setClientOrderId("client-1")
                .setIdempotencyKey("idem-1")
                .setRequestedAtMicros(NOW)
                .setAttributes(Map.of())
                .build();
    }

    private RiskDecisionEvent riskDecision() {
        return RiskDecisionEvent.newBuilder()
                .setEventId("evt-risk-1")
                .setSchemaVersion(1)
                .setDecisionId("risk-decision:cmd-1")
                .setCommandId("cmd-1")
                .setSignalId("sig-1")
                .setStrategyId("lfa")
                .setProvider("binance")
                .setEnvironment("demo")
                .setAccount("main")
                .setMarket("usd_m_futures")
                .setSymbol("BTCUSDT")
                .setDecision(RiskDecision.APPROVED)
                .setReasons(List.of("pause_governance:override", "risk_gate:approved"))
                .setMaxQuantity(null)
                .setMaxNotional(null)
                .setDecidedAtMicros(NOW)
                .setAttributes(Map.of(
                        "pause_override_requested",
                        "true",
                        "pause_override_allowed",
                        "true",
                        "pause_override_by",
                        "operator",
                        "pause_override_reason",
                        "controlled test order",
                        "pause_override_expires_at",
                        NOW.plusSeconds(60).toString()
                ))
                .build();
    }
}
