package io.github.manu.observability;

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

class PauseGovernanceGaugeBinderTest {

    private static final Instant NOW = Instant.parse("2026-06-07T13:30:00Z");

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TradingStateProjection projection = new TradingStateProjection();

    @Test
    void exposes_effective_active_pause_counts_by_scope() {
        projection.restore(snapshot(List.of(
                pause("ACCOUNT", "main", null, "PAUSE_ACCOUNT", true, Map.of()),
                pause("SYMBOL", "BTCUSDT", "BTCUSDT", "PAUSE_SYMBOL", true, Map.of()),
                pause("SYMBOL", "ETHUSDT", "ETHUSDT", "PAUSE_SYMBOL", true, Map.of(
                        "pause_expires_at",
                        NOW.minusSeconds(1).toString()
                )),
                pause("SYMBOL", "BNBUSDT", "BNBUSDT", "RELEASE_SYMBOL_PAUSE", false, Map.of())
        )));

        new PauseGovernanceGaugeBinder(
                meterRegistry,
                projection,
                Clock.fixed(NOW, ZoneOffset.UTC)
        ).bind();

        assertThat(activeGauge("ACCOUNT")).isEqualTo(1.0d);
        assertThat(activeGauge("SYMBOL")).isEqualTo(1.0d);

        projection.restore(snapshot(List.of()));

        assertThat(activeGauge("ACCOUNT")).isZero();
        assertThat(activeGauge("SYMBOL")).isZero();
    }

    private double activeGauge(String scope) {
        return meterRegistry.get("trading.pause_governance.active.states")
                .tag("scope", scope)
                .gauge()
                .value();
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
            String action,
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
                action,
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
