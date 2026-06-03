package io.github.manu.intervention;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.InterventionAcknowledgementEvent;
import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterventionRemediationOrchestratorTest {

    private static final Instant NOW = Instant.parse("2026-06-03T09:00:00Z");

    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
    private final TradingStateProjection projection = new TradingStateProjection();
    private final InterventionRemediationOrchestrator orchestrator = new InterventionRemediationOrchestrator(
            eventBus,
            projection,
            properties(true, true)
    );

    @Test
    void publishes_order_acknowledgement_for_operator_review_decision() {
        restoreOrderIntervention();

        orchestrator.handle(envelope(orderDecision())).join();

        assertThat(eventBus.envelopes).singleElement().satisfies(envelope -> {
            assertThat(envelope.eventType()).isEqualTo(TradingEventType.INTERVENTION_ACKNOWLEDGEMENT);
            assertThat(envelope.key().getPartitionKey().toString())
                    .isEqualTo("intervention_acknowledgement|order|binance|demo|main|usd_m_futures|btcusdt|client-1");
            InterventionAcknowledgementEvent event = (InterventionAcknowledgementEvent) envelope.value();
            assertThat(event.getEventId()).hasToString("evt:intervention-ack:remediation-001");
            assertThat(event.getAcknowledgementId()).hasToString("intervention-ack:remediation-001");
            assertThat(event.getClientOrderId()).hasToString("client-1");
            assertThat(event.getInterventionReason()).hasToString("external_order_observed");
            assertThat(event.getAcknowledgedBy()).hasToString("operator");
            assertThat(event.getAcknowledgementReason()).hasToString("reviewed current projection");
            assertThat(event.getAcknowledgedAtMicros()).isEqualTo(NOW);
            assertThat(event.getAttributes())
                    .containsEntry("ticket", "ops-789")
                    .containsEntry("remediation_id", "remediation-001")
                    .containsEntry("remediation_event_id", "evt-remediation-001")
                    .containsEntry("remediation_scope", "ORDER")
                    .containsEntry("remediation_action", "OPERATOR_REVIEW");
        });
    }

    @Test
    void suppresses_duplicate_live_order_decisions_after_acknowledgement_publish() {
        restoreOrderIntervention();

        orchestrator.handle(envelope(orderDecision())).join();
        orchestrator.handle(envelope(orderDecision())).join();

        assertThat(eventBus.envelopes).hasSize(1);
    }

    @Test
    void ignores_order_decision_when_intervention_is_already_resolved() {
        restoreOrderIntervention("external_order_observed", false);

        orchestrator.handle(envelope(orderDecision())).join();

        assertThat(eventBus.envelopes).isEmpty();
    }

    @Test
    void publishes_position_acknowledgement_for_operator_review_decision() {
        restorePositionIntervention();

        orchestrator.handle(envelope(positionDecision())).join();

        assertThat(eventBus.envelopes).singleElement().satisfies(envelope -> {
            assertThat(envelope.eventType()).isEqualTo(TradingEventType.INTERVENTION_ACKNOWLEDGEMENT);
            assertThat(envelope.key().getPartitionKey().toString())
                    .isEqualTo("intervention_acknowledgement|symbol|binance|demo|main|usd_m_futures|btcusdt|btcusdt");
            InterventionAcknowledgementEvent event = (InterventionAcknowledgementEvent) envelope.value();
            assertThat(event.getClientOrderId()).isNull();
            assertThat(event.getInterventionReason()).hasToString("external_position_change");
            assertThat(event.getAttributes())
                    .containsEntry("position_side", "BOTH")
                    .containsEntry("remediation_scope", "POSITION")
                    .containsEntry("remediation_action", "OPERATOR_REVIEW");
        });
    }

    @Test
    void suppresses_duplicate_live_position_decisions_after_acknowledgement_publish() {
        restorePositionIntervention();

        orchestrator.handle(envelope(positionDecision())).join();
        orchestrator.handle(envelope(positionDecision())).join();

        assertThat(eventBus.envelopes).hasSize(1);
    }

    @Test
    void ignores_position_decision_when_intervention_is_already_resolved() {
        restorePositionIntervention(false);

        orchestrator.handle(envelope(positionDecision())).join();

        assertThat(eventBus.envelopes).isEmpty();
    }

    @Test
    void ignores_operator_review_decision_when_acknowledgement_workflow_is_disabled() {
        InterventionRemediationOrchestrator disabled = new InterventionRemediationOrchestrator(
                eventBus,
                projection,
                properties(true, false)
        );

        disabled.handle(envelope(orderDecision())).join();

        assertThat(eventBus.envelopes).isEmpty();
    }

    @Test
    void ignores_non_operator_review_actions() {
        restoreOrderIntervention();

        orchestrator.handle(envelope(orderDecision("REPLAN_FROM_PROJECTION"))).join();

        assertThat(eventBus.envelopes).isEmpty();
    }

    @Test
    void rejects_order_decision_when_projection_no_longer_has_matching_intervention() {
        restoreOrderIntervention("unplanned_managed_order_change", true);

        assertThatThrownBy(() -> orchestrator.handle(envelope(orderDecision())).join())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Projected order intervention reason does not match remediation");
        assertThat(eventBus.envelopes).isEmpty();
    }

    private RemediationDecisionEvent orderDecision() {
        return orderDecision("OPERATOR_REVIEW");
    }

    private RemediationDecisionEvent orderDecision(String action) {
        return RemediationDecisionEvent.newBuilder()
                .setEventId("evt-remediation-001")
                .setSchemaVersion(1)
                .setRemediationId("remediation-001")
                .setProvider("binance")
                .setEnvironment("demo")
                .setAccount("main")
                .setMarket("usd_m_futures")
                .setSymbol("BTCUSDT")
                .setScope("ORDER")
                .setAction(action)
                .setClientOrderId("client-1")
                .setPositionSide(null)
                .setInterventionReason("external_order_observed")
                .setReasons(List.of("intervention:external_order_observed"))
                .setDecidedBy("operator")
                .setDecisionReason("reviewed current projection")
                .setDecidedAtMicros(NOW)
                .setAttributes(Map.of("ticket", "ops-789"))
                .build();
    }

    private RemediationDecisionEvent positionDecision() {
        return RemediationDecisionEvent.newBuilder(orderDecision())
                .setScope("POSITION")
                .setClientOrderId(null)
                .setPositionSide("BOTH")
                .setInterventionReason("external_position_change")
                .setReasons(List.of("intervention:external_position_change"))
                .build();
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
                        "BTCUSDT"
                ),
                event
        );
    }

    private void restoreOrderIntervention() {
        restoreOrderIntervention("external_order_observed", true);
    }

    private void restoreOrderIntervention(String interventionReason, boolean externalIntervention) {
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
                        externalIntervention,
                        interventionReason,
                        NOW.minusSeconds(1),
                        "evt-order-intervention"
                )),
                List.of(),
                List.of()
        ));
    }

    private void restorePositionIntervention() {
        restorePositionIntervention(true);
    }

    private void restorePositionIntervention(boolean externalIntervention) {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(new TradingStateProjection.PositionState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "BOTH",
                        "0",
                        "50000.00",
                        "50010.00",
                        "0",
                        "USER_DATA",
                        externalIntervention,
                        "external_position_change",
                        NOW.minusSeconds(1),
                        "evt-position-intervention"
                )),
                List.of(),
                List.of(),
                List.of()
        ));
    }

    private InterventionProperties properties(boolean enabled, boolean operatorReviewAcknowledgementEnabled) {
        return new InterventionProperties(
                InterventionProperties.OperatorApi.disabled(),
                new InterventionProperties.RemediationOrchestrator(enabled, operatorReviewAcknowledgementEnabled, 100_000)
        );
    }

    private static final class CapturingTradingEventBus implements TradingEventBus {

        private final List<TradingEventEnvelope<? extends SpecificRecord>> envelopes = new ArrayList<>();

        @Override
        public CompletableFuture<PublishedTradingEvent> publish(
                TradingEventEnvelope<? extends SpecificRecord> envelope
        ) {
            envelopes.add(envelope);
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    envelope.eventType(),
                    envelope.route().topic(),
                    0,
                    envelopes.size()
            ));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this test");
        }
    }
}
