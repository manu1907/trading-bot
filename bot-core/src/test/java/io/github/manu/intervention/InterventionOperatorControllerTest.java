package io.github.manu.intervention;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class InterventionOperatorControllerTest {

    private static final Instant NOW = Instant.parse("2026-05-28T09:00:00Z");

    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
    private final TradingStateProjection projection = new TradingStateProjection();
    private final InterventionAcknowledgementService service = new InterventionAcknowledgementService(
            eventBus,
            projection,
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> "ack-001"
    );
    private final InterventionOperatorController controller = new InterventionOperatorController(
            service,
            projection,
            new InterventionProperties(new InterventionProperties.OperatorApi(true, "secret-token"))
    );
    private final WebTestClient client = WebTestClient.bindToController(controller).build();

    @Test
    void accepts_order_acknowledgement_when_token_matches_projection() {
        restoreOrderIntervention("external_order_observed", true);

        client.post()
                .uri("/internal/interventions/orders/acknowledgements")
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .bodyValue(request())
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("accepted")
                .jsonPath("$.eventType")
                .isEqualTo("INTERVENTION_ACKNOWLEDGEMENT");

        assertThat(eventBus.envelope).isNotNull();
        assertThat(eventBus.envelope.eventType()).isEqualTo(TradingEventType.INTERVENTION_ACKNOWLEDGEMENT);
    }

    @Test
    void rejects_order_acknowledgement_when_token_is_invalid() {
        restoreOrderIntervention("external_order_observed", true);

        client.post()
                .uri("/internal/interventions/orders/acknowledgements")
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "wrong-token")
                .bodyValue(request())
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("unauthorized");

        assertThat(eventBus.envelope).isNull();
    }

    @Test
    void lists_unresolved_order_interventions_when_token_matches() {
        restoreOrderIntervention("external_order_observed", true);

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/orders")
                        .queryParam("provider", "binance")
                        .queryParam("environment", "demo")
                        .queryParam("account", "main")
                        .queryParam("market", "usd_m_futures")
                        .build())
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.count")
                .isEqualTo(1)
                .jsonPath("$.interventions[0].symbol")
                .isEqualTo("BTCUSDT")
                .jsonPath("$.interventions[0].clientOrderId")
                .isEqualTo("client-1")
                .jsonPath("$.interventions[0].interventionReason")
                .isEqualTo("external_order_observed");
    }

    @Test
    void rejects_order_intervention_listing_when_token_is_invalid() {
        restoreOrderIntervention("external_order_observed", true);

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/orders")
                        .queryParam("provider", "binance")
                        .queryParam("environment", "demo")
                        .queryParam("account", "main")
                        .queryParam("market", "usd_m_futures")
                        .build())
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "wrong-token")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("unauthorized");
    }

    @Test
    void lists_unresolved_position_interventions_when_token_matches() {
        restorePositionIntervention("external_position_change", true);

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/positions")
                        .queryParam("provider", "binance")
                        .queryParam("environment", "demo")
                        .queryParam("account", "main")
                        .queryParam("market", "usd_m_futures")
                        .build())
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.count")
                .isEqualTo(1)
                .jsonPath("$.interventions[0].symbol")
                .isEqualTo("BTCUSDT")
                .jsonPath("$.interventions[0].positionSide")
                .isEqualTo("BOTH")
                .jsonPath("$.interventions[0].positionAmount")
                .isEqualTo("0")
                .jsonPath("$.interventions[0].interventionReason")
                .isEqualTo("external_position_change");
    }

    @Test
    void rejects_position_intervention_listing_when_token_is_invalid() {
        restorePositionIntervention("external_position_change", true);

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/interventions/positions")
                        .queryParam("provider", "binance")
                        .queryParam("environment", "demo")
                        .queryParam("account", "main")
                        .queryParam("market", "usd_m_futures")
                        .build())
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "wrong-token")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("unauthorized");
    }

    @Test
    void maps_projection_rejection_to_conflict() {
        client.post()
                .uri("/internal/interventions/orders/acknowledgements")
                .header(InterventionOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .bodyValue(request())
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("conflict")
                .jsonPath("$.message")
                .isEqualTo("No projected order exists for acknowledgement");

        assertThat(eventBus.envelope).isNull();
    }

    private Map<String, Object> request() {
        return Map.of(
                "provider", "binance",
                "environment", "demo",
                "account", "main",
                "market", "usd_m_futures",
                "symbol", "BTCUSDT",
                "clientOrderId", "client-1",
                "interventionReason", "external_order_observed",
                "acknowledgedBy", "operator",
                "acknowledgementReason", "reviewed in exchange console",
                "attributes", Map.of("ticket", "ops-123")
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
