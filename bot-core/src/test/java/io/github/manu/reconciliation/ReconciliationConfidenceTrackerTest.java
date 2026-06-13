package io.github.manu.reconciliation;

import io.github.manu.events.TradingEventType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationConfidenceTrackerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-26T10:15:30Z"), ZoneOffset.UTC);
    private final ReconciliationConfidenceTracker tracker = new ReconciliationConfidenceTracker(clock);

    @Test
    void reports_no_observations_for_unchecked_target() {
        ReconciliationTargetConfidence confidence = tracker.targetConfidence(
                "binance",
                "demo",
                "main",
                "usd_m_futures"
        );

        assertThat(confidence.status()).isEqualTo(ReconciliationTargetConfidence.Status.NO_OBSERVATIONS);
        assertThat(confidence.confident()).isFalse();
        assertThat(confidence.observedStates()).isZero();
        assertThat(confidence.degradedStates()).isZero();
        assertThat(confidence.observedAt()).isNull();
    }

    @Test
    void reports_confident_when_latest_observations_are_aligned() {
        tracker.record(observation(
                "USDT",
                ReconciliationConfidenceStatus.MISMATCH,
                List.of(new ReconciliationDifference("availableBalance", "600", "700"))
        ));
        tracker.record(observation("USDT", ReconciliationConfidenceStatus.CONFIDENT, List.of()));

        ReconciliationTargetConfidence confidence = tracker.targetConfidence(
                "binance",
                "demo",
                "main",
                "usd_m_futures"
        );

        assertThat(confidence.status()).isEqualTo(ReconciliationTargetConfidence.Status.CONFIDENT);
        assertThat(confidence.confident()).isTrue();
        assertThat(confidence.observedStates()).isEqualTo(1);
        assertThat(confidence.degradedStates()).isZero();
        assertThat(confidence.observedAt()).isEqualTo(Instant.parse("2026-05-26T10:15:30Z"));
        assertThat(tracker.degradedStates("binance", "demo", "main", "usd_m_futures")).isEmpty();
    }

    @Test
    void keeps_target_degraded_while_any_latest_observation_is_not_confident() {
        tracker.record(observation("USDT", ReconciliationConfidenceStatus.CONFIDENT, List.of()));
        tracker.record(observation(
                "BTCUSDT|long",
                ReconciliationConfidenceStatus.MISSING_PROJECTION,
                List.of()
        ));

        ReconciliationTargetConfidence confidence = tracker.targetConfidence(
                "binance",
                "demo",
                "main",
                "usd_m_futures"
        );

        assertThat(confidence.status()).isEqualTo(ReconciliationTargetConfidence.Status.DEGRADED);
        assertThat(confidence.confident()).isFalse();
        assertThat(confidence.observedStates()).isEqualTo(2);
        assertThat(confidence.degradedStates()).isEqualTo(1);
        assertThat(tracker.degradedStates("binance", "demo", "main", "usd_m_futures"))
                .singleElement()
                .satisfies(state -> {
                    assertThat(state.entityKey()).isEqualTo("binance|demo|main|usd_m_futures|BTCUSDT|long");
                    assertThat(state.status()).isEqualTo(ReconciliationConfidenceStatus.MISSING_PROJECTION);
                });
    }

    @Test
    void returns_sorted_target_states_for_availability_scoring() {
        tracker.record(observation("ETHUSDT|long", ReconciliationConfidenceStatus.CONFIDENT, List.of()));
        tracker.record(observation("BTCUSDT|long", ReconciliationConfidenceStatus.CONFIDENT, List.of()));

        assertThat(tracker.targetStates("binance", "demo", "main", "usd_m_futures"))
                .extracting(ReconciliationConfidenceState::entityKey)
                .containsExactly(
                        "binance|demo|main|usd_m_futures|BTCUSDT|long",
                        "binance|demo|main|usd_m_futures|ETHUSDT|long"
                );
    }

    private ReconciliationObservation observation(
            String entitySuffix,
            ReconciliationConfidenceStatus status,
            List<ReconciliationDifference> differences
    ) {
        return new ReconciliationObservation(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                TradingEventType.BALANCE_UPDATE,
                "binance|demo|main|usd_m_futures|" + entitySuffix,
                status,
                differences
        );
    }
}
