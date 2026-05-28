package io.github.manu.intervention;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.InterventionAcknowledgementEvent;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterventionAcknowledgementServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-28T08:00:00Z");

    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
    private final InterventionAcknowledgementService service = new InterventionAcknowledgementService(
            eventBus,
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> "ack-001"
    );

    @Test
    void publishes_order_intervention_acknowledgement_event() {
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
