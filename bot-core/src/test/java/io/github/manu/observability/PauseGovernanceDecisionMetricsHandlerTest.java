package io.github.manu.observability;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PauseGovernanceDecisionMetricsHandlerTest {

    private static final Instant NOW = Instant.parse("2026-06-07T14:00:00Z");

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final PauseGovernanceDecisionMetricsHandler handler =
            new PauseGovernanceDecisionMetricsHandler(new PauseGovernanceMetrics(meterRegistry));

    @Test
    void counts_pause_activation_decisions_with_valid_expiry() {
        handler.handle(envelope(decision("PAUSE_SYMBOL", "BTCUSDT", Map.of(
                "pause_expires_at",
                NOW.plusSeconds(900).toString()
        )))).join();

        assertThat(counter("trading.pause_governance.activation.events", "scope", "SYMBOL", "expiry_configured", "true"))
                .isEqualTo(1.0d);
        assertThat(counter("trading.pause_governance.expiry.configured.events", "scope", "SYMBOL"))
                .isEqualTo(1.0d);
    }

    @Test
    void counts_pause_activation_decisions_without_valid_expiry() {
        handler.handle(envelope(decision("PAUSE_ACCOUNT", null, Map.of(
                "pause_expires_at",
                "not-an-instant"
        )))).join();

        assertThat(counter("trading.pause_governance.activation.events", "scope", "ACCOUNT", "expiry_configured", "false"))
                .isEqualTo(1.0d);
        assertThat(meterRegistry.find("trading.pause_governance.expiry.configured.events").counter()).isNull();
    }

    @Test
    void ignores_non_activation_pause_governance_decisions() {
        handler.handle(envelope(decision("RELEASE_SYMBOL_PAUSE", "BTCUSDT", Map.of()))).join();

        assertThat(meterRegistry.find("trading.pause_governance.activation.events").counter()).isNull();
        assertThat(meterRegistry.find("trading.pause_governance.expiry.configured.events").counter()).isNull();
    }

    private double counter(String name, String firstTag, String firstValue, String secondTag, String secondValue) {
        return meterRegistry.get(name)
                .tag("provider", "binance")
                .tag("environment", "demo")
                .tag("account", "main")
                .tag("market", "usd_m_futures")
                .tag(firstTag, firstValue)
                .tag(secondTag, secondValue)
                .counter()
                .count();
    }

    private double counter(String name, String tag, String value) {
        return meterRegistry.get(name)
                .tag("provider", "binance")
                .tag("environment", "demo")
                .tag("account", "main")
                .tag("market", "usd_m_futures")
                .tag(tag, value)
                .counter()
                .count();
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
