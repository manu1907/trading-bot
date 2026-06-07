package io.github.manu.observability;

import io.github.manu.audit.AuditLogger;
import io.github.manu.audit.PauseGovernanceAuditTrail;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.RemediationDecisionEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PauseGovernanceDecisionAuditHandlerTest {

    private static final Instant NOW = Instant.parse("2026-06-07T15:00:00Z");

    private final PauseGovernanceAuditTrail auditTrail = new PauseGovernanceAuditTrail();
    private final PauseGovernanceDecisionAuditHandler handler =
            new PauseGovernanceDecisionAuditHandler(new AuditLogger(auditTrail));

    @Test
    void records_pause_activation_audit_events() {
        handler.handle(envelope(decision("PAUSE_SYMBOL", "BTCUSDT", Map.of(
                "pause_expires_at",
                NOW.plusSeconds(900).toString()
        )))).join();

        assertThat(auditTrail.recent("binance", "demo", "main", "usd_m_futures", 10))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo("pause_governance_activated");
                    assertThat(event.symbol()).isEqualTo("BTCUSDT");
                    assertThat(event.pauseScope()).isEqualTo("SYMBOL");
                    assertThat(event.pauseTarget()).isEqualTo("BTCUSDT");
                    assertThat(event.remediationId()).isEqualTo("remediation-PAUSE_SYMBOL");
                    assertThat(event.eventId()).isEqualTo("evt-PAUSE_SYMBOL");
                    assertThat(event.outcome()).isEqualTo("activated");
                    assertThat(event.actor()).isEqualTo("automated_remediation_policy");
                    assertThat(event.reason()).isEqualTo("policy selected pause governance");
                    assertThat(event.expiresAt()).isEqualTo(NOW.plusSeconds(900).toString());
                    assertThat(event.invalidReason()).isNull();
                    assertThat(event.occurredAt()).isEqualTo(NOW);
                });
    }

    @Test
    void records_invalid_expiry_reason_without_blocking_activation_audit() {
        handler.handle(envelope(decision("PAUSE_ACCOUNT", null, Map.of(
                "pause_expires_at",
                "not-an-instant"
        )))).join();

        assertThat(auditTrail.recent("binance", "demo", "main", "usd_m_futures", 10))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.pauseScope()).isEqualTo("ACCOUNT");
                    assertThat(event.pauseTarget()).isEqualTo("main");
                    assertThat(event.expiresAt()).isEqualTo("not-an-instant");
                    assertThat(event.invalidReason()).isEqualTo("invalid_pause_expires_at");
                });
    }

    @Test
    void ignores_non_activation_pause_governance_decisions() {
        handler.handle(envelope(decision("RELEASE_SYMBOL_PAUSE", "BTCUSDT", Map.of()))).join();

        assertThat(auditTrail.recent("binance", "demo", "main", "usd_m_futures", 10)).isEmpty();
    }

    private TradingEventEnvelope<RemediationDecisionEvent> envelope(RemediationDecisionEvent event) {
        return TradingEventEnvelope.of(
                TradingEventType.REMEDIATION_DECISION,
                TradingEventKeys.symbol(
                        TradingEventType.REMEDIATION_DECISION,
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        event.getSymbol() == null ? "main" : event.getSymbol().toString()
                ),
                event
        );
    }

    private RemediationDecisionEvent decision(
            String action,
            String symbol,
            Map<CharSequence, CharSequence> attributes
    ) {
        return RemediationDecisionEvent.newBuilder()
                .setEventId("evt-" + action)
                .setSchemaVersion(1)
                .setRemediationId("remediation-" + action)
                .setProvider("binance")
                .setEnvironment("demo")
                .setAccount("main")
                .setMarket("usd_m_futures")
                .setSymbol(symbol)
                .setScope("POSITION")
                .setAction(action)
                .setClientOrderId(null)
                .setPositionSide("BOTH")
                .setInterventionReason("external_position_change")
                .setReasons(List.of("intervention:external_position_change"))
                .setDecidedBy("automated_remediation_policy")
                .setDecisionReason("policy selected pause governance")
                .setDecidedAtMicros(NOW)
                .setAttributes(attributes)
                .build();
    }
}
