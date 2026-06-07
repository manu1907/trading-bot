package io.github.manu.intervention;

import io.github.manu.audit.AuditLogger;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.observability.PauseGovernanceMetrics;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PauseGovernanceControlServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-07T12:45:00Z");

    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
    private final TradingStateProjection projection = new TradingStateProjection();
    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final PauseGovernanceControlService service = new PauseGovernanceControlService(
            eventBus,
            projection,
            auditLogger,
            new PauseGovernanceMetrics(meterRegistry),
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> "release-001"
    );

    @Test
    void audits_pause_release_after_event_publication_succeeds() {
        restorePauseGovernance();

        service.release(new PauseGovernanceControlService.PauseReleaseRequest(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                "SYMBOL",
                "BTCUSDT",
                "operator",
                "risk cleared",
                Map.of("ticket", "ops-123")
        )).join();

        assertThat(eventBus.envelope).isNotNull();
        assertThat(eventBus.envelope.eventType()).isEqualTo(TradingEventType.REMEDIATION_DECISION);
        assertReleaseMetric("published");
        verify(auditLogger).pauseGovernanceReleased(
                any(RemediationDecisionEvent.class),
                eq("SYMBOL"),
                eq("BTCUSDT"),
                eq("remediation-pause-1")
        );
    }

    @Test
    void records_pause_release_publish_failure_without_success_audit() {
        restorePauseGovernance();
        eventBus.failure = new IllegalStateException("broker unavailable");

        PauseGovernanceControlService.PauseReleaseRequest request =
                new PauseGovernanceControlService.PauseReleaseRequest(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "SYMBOL",
                        "BTCUSDT",
                        "operator",
                        "risk cleared",
                        Map.of()
                );

        assertThatThrownBy(() -> service.release(request).join())
                .hasCause(eventBus.failure);
        assertReleaseMetric("publish_failed");
        verifyNoInteractions(auditLogger);
    }

    private void assertReleaseMetric(String outcome) {
        assertThat(meterRegistry.getMeters()
                .stream()
                .filter(meter -> "trading.pause_governance.release.events".equals(meter.getId().getName()))
                .toList())
                .singleElement()
                .satisfies(meter -> {
                    assertThat(meter.getId().getTag("provider")).isEqualTo("binance");
                    assertThat(meter.getId().getTag("environment")).isEqualTo("demo");
                    assertThat(meter.getId().getTag("account")).isEqualTo("main");
                    assertThat(meter.getId().getTag("market")).isEqualTo("usd_m_futures");
                    assertThat(meter.getId().getTag("scope")).isEqualTo("SYMBOL");
                    assertThat(meter.getId().getTag("outcome")).isEqualTo(outcome);
                    assertThat(meterRegistry.get(meter.getId().getName()).tags(meter.getId().getTags()).counter().count())
                            .isEqualTo(1.0d);
                });
    }

    private void restorePauseGovernance() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.PauseGovernanceState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "SYMBOL",
                        "BTCUSDT",
                        "BTCUSDT",
                        "remediation-pause-1",
                        "POSITION",
                        "PAUSE_SYMBOL",
                        "external_position_change",
                        List.of("intervention:external_position_change"),
                        "automated_remediation_policy",
                        "policy selected pause governance",
                        Map.of(),
                        true,
                        NOW.minusSeconds(1),
                        "evt-pause-governance"
                )),
                List.of("evt-pause-governance")
        ));
    }

    private static final class CapturingTradingEventBus implements TradingEventBus {

        private TradingEventEnvelope<? extends SpecificRecord> envelope;
        private RuntimeException failure;

        @Override
        public CompletableFuture<PublishedTradingEvent> publish(
                TradingEventEnvelope<? extends SpecificRecord> envelope
        ) {
            this.envelope = envelope;
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(new PublishedTradingEvent(envelope.eventType(), envelope.route().topic(), 0, 1));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this test");
        }
    }
}
