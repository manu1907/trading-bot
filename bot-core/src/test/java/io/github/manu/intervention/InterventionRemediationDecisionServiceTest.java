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

    @Test
    void rejects_order_remediation_without_complete_order_identity() {
        restoreOrderIntervention();

        InterventionRemediationDecisionService.RemediationDecisionRequest missingClientOrderId =
                new InterventionRemediationDecisionService.RemediationDecisionRequest(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "ORDER",
                        "OPERATOR_REVIEW",
                        null,
                        null,
                        "operator",
                        "reviewed current projection",
                        Map.of()
                );

        assertThatThrownBy(() -> service.decide(missingClientOrderId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("clientOrderId is required");
        assertThat(eventBus.envelope).isNull();
    }

    @Test
    void publishes_position_remediation_decision_when_request_matches_current_recommendation() {
        restorePositionIntervention();

        service.decide(positionRequest()).join();

        assertThat(eventBus.envelope).isNotNull();
        assertThat(eventBus.envelope.eventType()).isEqualTo(TradingEventType.REMEDIATION_DECISION);
        RemediationDecisionEvent event = (RemediationDecisionEvent) eventBus.envelope.value();
        assertThat(event.getScope()).isEqualTo("POSITION");
        assertThat(event.getAction()).isEqualTo("HEDGE_OR_REPLAN");
        assertThat(event.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(event.getPositionSide()).isEqualTo("BOTH");
        assertThat(event.getReasons()).containsExactly("intervention:external_position_change");
        assertThat(event.getAttributes()).containsEntry("position_amount", "0.01");
    }

    @Test
    void rejects_position_remediation_without_complete_position_identity() {
        restorePositionIntervention();

        InterventionRemediationDecisionService.RemediationDecisionRequest missingPositionSide =
                new InterventionRemediationDecisionService.RemediationDecisionRequest(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "POSITION",
                        "HEDGE_OR_REPLAN",
                        null,
                        null,
                        "operator",
                        "reviewed current projection",
                        Map.of()
                );

        assertThatThrownBy(() -> service.decide(missingPositionSide))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("positionSide is required");
        assertThat(eventBus.envelope).isNull();
    }

    @Test
    void publishes_manual_review_remediation_for_requested_decision_identity() {
        restoreManualReviewDecisions();

        service.decide(manualReviewRequest(Map.of(
                "decision_id",
                "risk-decision:cmd-2",
                "ticket",
                "ops-456"
        ))).join();

        assertThat(eventBus.envelope).isNotNull();
        assertThat(eventBus.envelope.eventType()).isEqualTo(TradingEventType.REMEDIATION_DECISION);
        RemediationDecisionEvent event = (RemediationDecisionEvent) eventBus.envelope.value();
        assertThat(event.getScope()).isEqualTo("MANUAL_REVIEW");
        assertThat(event.getAction()).isEqualTo("OPERATOR_REVIEW");
        assertThat(event.getClientOrderId()).isNull();
        assertThat(event.getReasons()).containsExactly("order_status:unknown");
        assertThat(event.getAttributes())
                .containsEntry("command_id", "cmd-2")
                .containsEntry("decision_id", "risk-decision:cmd-2")
                .containsEntry("ticket", "ops-456")
                .containsEntry("recommendation_event_id", "evt-review-2");
    }

    @Test
    void publishes_manual_review_remediation_for_affected_client_order_identity() {
        restoreManualReviewDecisions();

        service.decide(manualReviewRequest(
                "blocked-client-cmd-2",
                Map.of("ticket", "ops-457")
        )).join();

        assertThat(eventBus.envelope).isNotNull();
        RemediationDecisionEvent event = (RemediationDecisionEvent) eventBus.envelope.value();
        assertThat(event.getScope()).isEqualTo("MANUAL_REVIEW");
        assertThat(event.getClientOrderId()).isNull();
        assertThat(event.getAttributes())
                .containsEntry("command_id", "cmd-2")
                .containsEntry("unknown_order_client_order_ids", "blocked-client-cmd-2")
                .containsEntry("ticket", "ops-457")
                .containsEntry("recommendation_event_id", "evt-review-2");
    }

    @Test
    void publishes_manual_review_remediation_for_affected_exchange_order_identity() {
        restoreManualReviewDecisions();

        service.decide(manualReviewRequest(Map.of(
                "affected_exchange_order_id",
                "blocked-exchange-cmd-2",
                "ticket",
                "ops-458"
        ))).join();

        assertThat(eventBus.envelope).isNotNull();
        RemediationDecisionEvent event = (RemediationDecisionEvent) eventBus.envelope.value();
        assertThat(event.getScope()).isEqualTo("MANUAL_REVIEW");
        assertThat(event.getAttributes())
                .containsEntry("command_id", "cmd-2")
                .containsEntry("unknown_order_exchange_order_ids", "blocked-exchange-cmd-2")
                .containsEntry("affected_exchange_order_id", "blocked-exchange-cmd-2")
                .containsEntry("recommendation_event_id", "evt-review-2");
    }

    @Test
    void publishes_manual_review_remediation_for_external_order_client_identity() {
        restoreExternalOrderManualReviewDecision();

        service.decide(manualReviewRequest(
                "manual-client-1",
                Map.of("ticket", "ops-459")
        )).join();

        assertThat(eventBus.envelope).isNotNull();
        RemediationDecisionEvent event = (RemediationDecisionEvent) eventBus.envelope.value();
        assertThat(event.getScope()).isEqualTo("MANUAL_REVIEW");
        assertThat(event.getReasons()).containsExactly("intervention:external_order");
        assertThat(event.getAttributes())
                .containsEntry("command_id", "cmd-external-review")
                .containsEntry("external_order_client_order_ids", "manual-client-1")
                .containsEntry("external_order_exchange_order_ids", "98765")
                .containsEntry("ticket", "ops-459")
                .containsEntry("recommendation_event_id", "evt-external-review");
    }

    @Test
    void publishes_manual_review_remediation_for_external_order_exchange_identity() {
        restoreExternalOrderManualReviewDecision();

        service.decide(manualReviewRequest(Map.of(
                "affected_exchange_order_id",
                "98765",
                "ticket",
                "ops-460"
        ))).join();

        assertThat(eventBus.envelope).isNotNull();
        RemediationDecisionEvent event = (RemediationDecisionEvent) eventBus.envelope.value();
        assertThat(event.getScope()).isEqualTo("MANUAL_REVIEW");
        assertThat(event.getAttributes())
                .containsEntry("command_id", "cmd-external-review")
                .containsEntry("external_order_exchange_order_ids", "98765")
                .containsEntry("affected_exchange_order_id", "98765")
                .containsEntry("recommendation_event_id", "evt-external-review");
    }

    @Test
    void publishes_manual_review_remediation_for_external_position_identity() {
        restoreExternalPositionManualReviewDecision();

        service.decide(manualReviewRequest(
                null,
                "BOTH",
                Map.of("ticket", "ops-461")
        )).join();

        assertThat(eventBus.envelope).isNotNull();
        RemediationDecisionEvent event = (RemediationDecisionEvent) eventBus.envelope.value();
        assertThat(event.getScope()).isEqualTo("MANUAL_REVIEW");
        assertThat(event.getReasons()).containsExactly("intervention:external_position");
        assertThat(event.getPositionSide()).isNull();
        assertThat(event.getAttributes())
                .containsEntry("command_id", "cmd-position-review")
                .containsEntry("external_position_symbols", "BTCUSDT")
                .containsEntry("external_position_sides", "BOTH")
                .containsEntry("ticket", "ops-461")
                .containsEntry("recommendation_event_id", "evt-position-review");
    }

    @Test
    void rejects_manual_review_remediation_without_decision_identity() {
        restoreManualReviewDecisions();

        assertThatThrownBy(() -> service.decide(manualReviewRequest(Map.of("ticket", "ops-456"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "manual review remediation requires command_id, decision_id, clientOrderId, "
                                + "positionSide, affected_order_command_id, or affected_exchange_order_id"
                );
        assertThat(eventBus.envelope).isNull();
    }

    @Test
    void rejects_manual_review_remediation_when_decision_identity_no_longer_matches() {
        restoreManualReviewDecisions();

        assertThatThrownBy(() -> service.decide(manualReviewRequest(Map.of("decision_id", "risk-decision:missing"))))
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

    private InterventionRemediationDecisionService.RemediationDecisionRequest positionRequest() {
        return new InterventionRemediationDecisionService.RemediationDecisionRequest(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                "BTCUSDT",
                "POSITION",
                "HEDGE_OR_REPLAN",
                null,
                "BOTH",
                "operator",
                "reviewed current projection",
                Map.of("ticket", "ops-790")
        );
    }

    private InterventionRemediationDecisionService.RemediationDecisionRequest manualReviewRequest(
            Map<CharSequence, CharSequence> attributes
    ) {
        return manualReviewRequest(null, attributes);
    }

    private InterventionRemediationDecisionService.RemediationDecisionRequest manualReviewRequest(
            String clientOrderId,
            Map<CharSequence, CharSequence> attributes
    ) {
        return manualReviewRequest(clientOrderId, null, attributes);
    }

    private InterventionRemediationDecisionService.RemediationDecisionRequest manualReviewRequest(
            String clientOrderId,
            String positionSide,
            Map<CharSequence, CharSequence> attributes
    ) {
        return new InterventionRemediationDecisionService.RemediationDecisionRequest(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                "BTCUSDT",
                "MANUAL_REVIEW",
                "OPERATOR_REVIEW",
                clientOrderId,
                positionSide,
                "operator",
                "reviewed current projection",
                attributes
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

    private void restorePositionIntervention() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(new TradingStateProjection.PositionState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "BOTH",
                        "0.01",
                        "50000.00",
                        "50010.00",
                        "1.10",
                        "USER_DATA",
                        true,
                        "external_position_change",
                        NOW.minusSeconds(1),
                        "evt-position-intervention"
                )),
                List.of(),
                List.of(),
                List.of()
        ));
    }

    private void restoreManualReviewDecisions() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.OrderState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "cmd-unknown",
                        "client-unknown",
                        "12345",
                        "UNKNOWN",
                        null,
                        "50000.00",
                        "0.001",
                        null,
                        null,
                        null,
                        "ORDER_RESULT",
                        null,
                        true,
                        false,
                        null,
                        NOW.minusSeconds(2),
                        "evt-unknown-order"
                )),
                List.of(),
                List.of(
                        manualReviewDecision("cmd-1", "risk-decision:cmd-1", "evt-review-1"),
                        manualReviewDecision("cmd-2", "risk-decision:cmd-2", "evt-review-2")
                ),
                List.of()
        ));
    }

    private void restoreExternalOrderManualReviewDecision() {
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
                        "manual-client-1",
                        "98765",
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
                        NOW.minusSeconds(2),
                        "evt-external-order"
                )),
                List.of(),
                List.of(new TradingStateProjection.ManualReviewDecisionState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "cmd-external-review",
                        "sig-1",
                        "lfa",
                        "risk-decision:cmd-external-review",
                        List.of("intervention:external_order"),
                        Map.of(
                                "external_order_intervention_action",
                                "MANUAL_REVIEW",
                                "external_order_client_order_ids",
                                "manual-client-1",
                                "external_order_exchange_order_ids",
                                "98765"
                        ),
                        NOW.minusSeconds(1),
                        "evt-external-review"
                )),
                List.of()
        ));
    }

    private void restoreExternalPositionManualReviewDecision() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(new TradingStateProjection.PositionState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "BOTH",
                        "0.01",
                        "50000.00",
                        "50010.00",
                        "1.10",
                        "USER_DATA",
                        true,
                        "external_position_change",
                        NOW.minusSeconds(2),
                        "evt-external-position"
                )),
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.ManualReviewDecisionState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "cmd-position-review",
                        "sig-1",
                        "lfa",
                        "risk-decision:cmd-position-review",
                        List.of("intervention:external_position"),
                        Map.of(
                                "external_position_intervention_action",
                                "MANUAL_REVIEW",
                                "external_position_symbols",
                                "BTCUSDT",
                                "external_position_sides",
                                "BOTH"
                        ),
                        NOW.minusSeconds(1),
                        "evt-position-review"
                )),
                List.of()
        ));
    }

    private TradingStateProjection.ManualReviewDecisionState manualReviewDecision(
            String commandId,
            String decisionId,
            String eventId
    ) {
        return new TradingStateProjection.ManualReviewDecisionState(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                "BTCUSDT",
                commandId,
                "sig-1",
                "lfa",
                decisionId,
                List.of("order_status:unknown"),
                Map.of(
                        "unknown_order_status_action",
                        "MANUAL_REVIEW",
                        "unknown_order_command_ids",
                        "blocked-" + commandId,
                        "unknown_order_client_order_ids",
                        "blocked-client-" + commandId,
                        "unknown_order_exchange_order_ids",
                        "blocked-exchange-" + commandId
                ),
                NOW.minusSeconds(1),
                eventId
        );
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
