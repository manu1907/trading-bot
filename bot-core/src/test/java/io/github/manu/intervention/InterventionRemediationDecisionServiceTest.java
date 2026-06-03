package io.github.manu.intervention;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
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

class InterventionRemediationDecisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-03T08:00:00Z");

    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
    private final TradingStateProjection projection = new TradingStateProjection();
    private final InterventionRemediationAdvisor advisor = new InterventionRemediationAdvisor(projection);
    private final InterventionRemediationDecisionService service = new InterventionRemediationDecisionService(
            eventBus,
            advisor,
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> "decision-001"
    );

    @Test
    void publishes_remediation_decision_when_request_matches_current_recommendation() {
        restoreOrderIntervention();

        service.decide(request()).join();

        assertThat(eventBus.envelope).isNotNull();
        assertThat(eventBus.envelope.eventType()).isEqualTo(TradingEventType.REMEDIATION_DECISION);
        assertThat(eventBus.envelope.key().getEntityId()).isEqualTo("client-1");
        RemediationDecisionEvent event = (RemediationDecisionEvent) eventBus.envelope.value();
        assertThat(event.getRemediationId()).isEqualTo("remediation:decision-001");
        assertThat(event.getScope()).isEqualTo("ORDER");
        assertThat(event.getAction()).isEqualTo("OPERATOR_REVIEW");
        assertThat(event.getClientOrderId()).isEqualTo("client-1");
        assertThat(event.getReasons()).containsExactly("intervention:external_order_observed");
        assertThat(event.getDecidedBy()).isEqualTo("operator");
        assertThat(event.getDecisionReason()).isEqualTo("reviewed current projection");
        assertThat(event.getDecidedAtMicros()).isEqualTo(NOW);
        assertThat(event.getAttributes())
                .containsEntry("ticket", "ops-789")
                .containsEntry("recommendation_event_id", "evt-order-intervention");
    }

    @Test
    void rejects_decision_when_recommendation_no_longer_matches_projection() {
        restoreOrderIntervention();

        InterventionRemediationDecisionService.RemediationDecisionRequest staleRequest =
                new InterventionRemediationDecisionService.RemediationDecisionRequest(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "ORDER",
                        "HEDGE_OR_REPLAN",
                        "client-1",
                        null,
                        "operator",
                        "wrong action",
                        Map.of()
                );

        assertThatThrownBy(() -> service.decide(staleRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No matching remediation recommendation exists");
        assertThat(eventBus.envelope).isNull();
    }

    private InterventionRemediationDecisionService.RemediationDecisionRequest request() {
        return new InterventionRemediationDecisionService.RemediationDecisionRequest(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                "BTCUSDT",
                "ORDER",
                "OPERATOR_REVIEW",
                "client-1",
                null,
                "operator",
                "reviewed current projection",
                Map.of("ticket", "ops-789")
        );
    }

    private void restoreOrderIntervention() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.OrderState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        null,
                        "client-1",
                        "12345",
                        "ACCEPTED",
                        "NEW",
                        "50000.00",
                        "0.001",
                        "0",
                        null,
                        null,
                        "USER_DATA",
                        "NEW",
                        false,
                        true,
                        "external_order_observed",
                        NOW.minusSeconds(1),
                        "evt-order-intervention"
                )),
                List.of(),
                List.of()
        ));
    }

    private static final class CapturingTradingEventBus implements TradingEventBus {

        private TradingEventEnvelope<? extends SpecificRecord> envelope;

        @Override
        public CompletableFuture<PublishedTradingEvent> publish(
                TradingEventEnvelope<? extends SpecificRecord> envelope
        ) {
            this.envelope = envelope;
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    envelope.eventType(),
                    envelope.route().topic(),
                    0,
                    1
            ));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this test");
        }
    }
}
