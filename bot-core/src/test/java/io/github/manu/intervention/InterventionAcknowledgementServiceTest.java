package io.github.manu.intervention;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.InterventionAcknowledgementEvent;
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

class InterventionAcknowledgementServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-28T08:00:00Z");

    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
    private final TradingStateProjection projection = new TradingStateProjection();
    private final InterventionAcknowledgementService service = new InterventionAcknowledgementService(
            eventBus,
            projection,
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> "ack-001"
    );

    @Test
    void publishes_order_intervention_acknowledgement_event() {
        restoreOrderIntervention("external_order_observed", true);

        PublishedTradingEvent published = service.acknowledgeOrder(request()).join();

        assertThat(published.eventType()).isEqualTo(TradingEventType.INTERVENTION_ACKNOWLEDGEMENT);
        assertThat(eventBus.envelope).isNotNull();
        assertThat(eventBus.envelope.eventType()).isEqualTo(TradingEventType.INTERVENTION_ACKNOWLEDGEMENT);
        assertThat(eventBus.envelope.key().getPartitionKey().toString())
                .isEqualTo("intervention_acknowledgement|order|binance|demo|main|usd_m_futures|btcusdt|client-1");
        InterventionAcknowledgementEvent event = (InterventionAcknowledgementEvent) eventBus.envelope.value();
        assertThat(event.getEventId()).hasToString("evt:intervention-ack:ack-001");
        assertThat(event.getAcknowledgementId()).hasToString("intervention-ack:ack-001");
        assertThat(event.getInterventionReason()).hasToString("external_order_observed");
        assertThat(event.getAcknowledgedBy()).hasToString("operator");
        assertThat(event.getAcknowledgementReason()).hasToString("reviewed in exchange console");
        assertThat(event.getAcknowledgedAtMicros()).isEqualTo(NOW);
        assertThat(event.getAttributes()).containsEntry("ticket", "ops-123");
    }

    @Test
    void publishes_position_intervention_acknowledgement_event() {
        restorePositionIntervention("external_position_change", true);

        PublishedTradingEvent published = service.acknowledgePosition(positionRequest()).join();

        assertThat(published.eventType()).isEqualTo(TradingEventType.INTERVENTION_ACKNOWLEDGEMENT);
        assertThat(eventBus.envelope).isNotNull();
        assertThat(eventBus.envelope.eventType()).isEqualTo(TradingEventType.INTERVENTION_ACKNOWLEDGEMENT);
        assertThat(eventBus.envelope.key().getPartitionKey().toString())
                .isEqualTo("intervention_acknowledgement|symbol|binance|demo|main|usd_m_futures|btcusdt|btcusdt");
        InterventionAcknowledgementEvent event = (InterventionAcknowledgementEvent) eventBus.envelope.value();
        assertThat(event.getEventId()).hasToString("evt:intervention-ack:ack-001");
        assertThat(event.getAcknowledgementId()).hasToString("intervention-ack:ack-001");
        assertThat(event.getClientOrderId()).isNull();
        assertThat(event.getInterventionReason()).hasToString("external_position_change");
        assertThat(event.getAcknowledgedBy()).hasToString("operator");
        assertThat(event.getAcknowledgementReason()).hasToString("reviewed in exchange console");
        assertThat(event.getAttributes()).containsEntry("position_side", "BOTH");
        assertThat(event.getAttributes()).containsEntry("ticket", "ops-456");
    }

    @Test
    void rejects_missing_required_identity() {
        InterventionAcknowledgementService.OrderInterventionAcknowledgementRequest request =
                new InterventionAcknowledgementService.OrderInterventionAcknowledgementRequest(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        " ",
                        "external_order_observed",
                        "operator",
                        "reviewed",
                        Map.of()
                );

        assertThatThrownBy(() -> service.acknowledgeOrder(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("clientOrderId is required");
    }

    @Test
    void rejects_missing_operator_reason() {
        restoreOrderIntervention("external_order_observed", true);

        InterventionAcknowledgementService.OrderInterventionAcknowledgementRequest request =
                new InterventionAcknowledgementService.OrderInterventionAcknowledgementRequest(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "client-1",
                        "external_order_observed",
                        "operator",
                        " ",
                        Map.of()
                );

        assertThatThrownBy(() -> service.acknowledgeOrder(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("acknowledgementReason is required");
    }

    @Test
    void rejects_missing_intervention_reason() {
        InterventionAcknowledgementService.OrderInterventionAcknowledgementRequest request =
                new InterventionAcknowledgementService.OrderInterventionAcknowledgementRequest(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "client-1",
                        " ",
                        "operator",
                        "reviewed",
                        Map.of()
                );

        assertThatThrownBy(() -> service.acknowledgeOrder(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("interventionReason is required");
        assertThat(eventBus.envelope).isNull();
    }

    @Test
    void rejects_missing_projected_order() {
        assertThatThrownBy(() -> service.acknowledgeOrder(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No projected order exists for acknowledgement");
        assertThat(eventBus.envelope).isNull();
    }

    @Test
    void rejects_order_without_unresolved_intervention() {
        restoreOrderIntervention("external_order_observed", false);

        assertThatThrownBy(() -> service.acknowledgeOrder(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Projected order has no unresolved intervention");
        assertThat(eventBus.envelope).isNull();
    }

    @Test
    void rejects_intervention_reason_mismatch() {
        restoreOrderIntervention("unplanned_managed_order_change", true);

        assertThatThrownBy(() -> service.acknowledgeOrder(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Projected order intervention reason does not match acknowledgement");
        assertThat(eventBus.envelope).isNull();
    }

    @Test
    void rejects_missing_projected_position() {
        assertThatThrownBy(() -> service.acknowledgePosition(positionRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No projected position exists for acknowledgement");
        assertThat(eventBus.envelope).isNull();
    }

    @Test
    void rejects_position_without_unresolved_intervention() {
        restorePositionIntervention("external_position_change", false);

        assertThatThrownBy(() -> service.acknowledgePosition(positionRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Projected position has no unresolved intervention");
        assertThat(eventBus.envelope).isNull();
    }

    @Test
    void rejects_position_intervention_reason_mismatch() {
        restorePositionIntervention("manual_position_close", true);

        assertThatThrownBy(() -> service.acknowledgePosition(positionRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Projected position intervention reason does not match acknowledgement");
        assertThat(eventBus.envelope).isNull();
    }

    private InterventionAcknowledgementService.OrderInterventionAcknowledgementRequest request() {
        return new InterventionAcknowledgementService.OrderInterventionAcknowledgementRequest(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                "BTCUSDT",
                "client-1",
                "external_order_observed",
                "operator",
                "reviewed in exchange console",
                Map.of("ticket", "ops-123")
        );
    }

    private InterventionAcknowledgementService.PositionInterventionAcknowledgementRequest positionRequest() {
        return new InterventionAcknowledgementService.PositionInterventionAcknowledgementRequest(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                "BTCUSDT",
                "BOTH",
                "external_position_change",
                "operator",
                "reviewed in exchange console",
                Map.of("ticket", "ops-456")
        );
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

    private void restorePositionIntervention(String interventionReason, boolean externalIntervention) {
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
                        "5",
                        "cross",
                        null,
                        "USER_DATA",
                        externalIntervention,
                        interventionReason,
                        NOW.minusSeconds(1),
                        "evt-position-intervention"
                )),
                List.of(),
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
