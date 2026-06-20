package io.github.manu.strategy.lfa;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.execution.ExecutionProperties;
import io.github.manu.intervention.InterventionProperties;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

class LfaLifecycleOperatorControllerTest {

    private final NoopTradingEventBus eventBus = new NoopTradingEventBus();
    private final TradingStateProjection projection = new TradingStateProjection();
    private final LfaSignalRunner runner = runner("PAUSED", projection, eventBus);
    private final LfaLifecycleOperatorController controller = new LfaLifecycleOperatorController(
            runner,
            new InterventionProperties(new InterventionProperties.OperatorApi(true, "secret-token"), null, null, null, null)
    );
    private final WebTestClient client = WebTestClient.bindToController(controller).build();

    @Test
    void rejects_lifecycle_status_when_token_is_invalid() {
        client.get()
                .uri("/internal/strategy/lfa/lifecycle")
                .header(LfaLifecycleOperatorController.OPERATOR_TOKEN_HEADER, "wrong-token")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("unauthorized");
    }

    @Test
    void returns_current_lifecycle_status_when_token_matches() {
        client.get()
                .uri("/internal/strategy/lfa/lifecycle")
                .header(LfaLifecycleOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.configuredLifecycleState")
                .isEqualTo("PAUSED")
                .jsonPath("$.effectiveLifecycleState")
                .isEqualTo("PAUSED")
                .jsonPath("$.publishEnabled")
                .isEqualTo(false)
                .jsonPath("$.allowedNextLifecycleStates[0]")
                .isEqualTo("ACTIVE")
                .jsonPath("$.emergencyStopReactivationAllowed")
                .isEqualTo(false)
                .jsonPath("$.openOrderCount")
                .isEqualTo(0)
                .jsonPath("$.openPositionCount")
                .isEqualTo(0)
                .jsonPath("$.drainComplete")
                .isEqualTo(true)
                .jsonPath("$.blockers[0]")
                .isEqualTo("lfa_lifecycle:paused");
    }

    @Test
    void transitions_lifecycle_when_token_matches() {
        client.post()
                .uri("/internal/strategy/lfa/lifecycle")
                .header(LfaLifecycleOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .bodyValue(new LfaLifecycleOperatorController.LifecycleTransitionRequest(
                        "ACTIVE",
                        "operator",
                        "promotion gate passed"
                ))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.configuredLifecycleState")
                .isEqualTo("PAUSED")
                .jsonPath("$.effectiveLifecycleState")
                .isEqualTo("ACTIVE")
                .jsonPath("$.publishEnabled")
                .isEqualTo(true)
                .jsonPath("$.changedBy")
                .isEqualTo("operator")
                .jsonPath("$.reason")
                .isEqualTo("promotion gate passed")
                .jsonPath("$.eventId")
                .exists();
        org.assertj.core.api.Assertions.assertThat(eventBus.envelope.eventType())
                .isEqualTo(TradingEventType.STRATEGY_LIFECYCLE);
        org.assertj.core.api.Assertions.assertThat(projection.strategyLifecycle(
                        "lfa",
                        "binance",
                        "demo",
                        "main",
                        "usdm_futures"
                ))
                .get()
                .satisfies(state -> {
                    org.assertj.core.api.Assertions.assertThat(state.previousLifecycleState()).isEqualTo("PAUSED");
                    org.assertj.core.api.Assertions.assertThat(state.lifecycleState()).isEqualTo("ACTIVE");
                    org.assertj.core.api.Assertions.assertThat(state.changedBy()).isEqualTo("operator");
                });
    }

    @Test
    void emergency_stop_transition_records_projected_exposure_evidence() {
        TradingStateProjection emergencyProjection = new TradingStateProjection();
        emergencyProjection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(openPosition()),
                List.of(openOrder()),
                List.of(),
                List.of()
        ));
        NoopTradingEventBus emergencyEventBus = new NoopTradingEventBus();
        LfaSignalRunner emergencyRunner = runner("ACTIVE", emergencyProjection, emergencyEventBus);
        LfaLifecycleOperatorController emergencyController = new LfaLifecycleOperatorController(
                emergencyRunner,
                new InterventionProperties(new InterventionProperties.OperatorApi(true, "secret-token"), null, null, null, null)
        );

        WebTestClient.bindToController(emergencyController)
                .build()
                .post()
                .uri("/internal/strategy/lfa/lifecycle")
                .header(LfaLifecycleOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .bodyValue(new LfaLifecycleOperatorController.LifecycleTransitionRequest(
                        "EMERGENCY_STOP",
                        "operator",
                        "operator kill switch"
                ))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.effectiveLifecycleState")
                .isEqualTo("EMERGENCY_STOP")
                .jsonPath("$.publishEnabled")
                .isEqualTo(false)
                .jsonPath("$.openOrderCount")
                .isEqualTo(1)
                .jsonPath("$.openPositionCount")
                .isEqualTo(1)
                .jsonPath("$.drainComplete")
                .isEqualTo(false);

        org.assertj.core.api.Assertions.assertThat(emergencyProjection.strategyLifecycle(
                        "lfa",
                        "binance",
                        "demo",
                        "main",
                        "usdm_futures"
                ))
                .get()
                .satisfies(state -> org.assertj.core.api.Assertions.assertThat(state.attributes())
                        .containsEntry("open_order_count", "1")
                        .containsEntry("open_position_count", "1")
                        .containsEntry("drain_complete", "false")
                        .containsEntry("emergency_stop_transition", "true")
                        .containsEntry("emergency_stop_reactivation_allowed", "false"));
    }

    @Test
    void rejects_unknown_lifecycle_transition() {
        client.post()
                .uri("/internal/strategy/lfa/lifecycle")
                .header(LfaLifecycleOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .bodyValue(new LfaLifecycleOperatorController.LifecycleTransitionRequest(
                        "BROKEN",
                        "operator",
                        "bad state"
                ))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("bad_request")
                .jsonPath("$.message")
                .isEqualTo("lifecycleState must be a known LFA lifecycle state");
    }

    @Test
    void rejects_disallowed_lifecycle_transition() {
        LfaSignalRunner emergencyRunner = runner("EMERGENCY_STOP", new TradingStateProjection(), new NoopTradingEventBus());
        LfaLifecycleOperatorController emergencyController = new LfaLifecycleOperatorController(
                emergencyRunner,
                new InterventionProperties(new InterventionProperties.OperatorApi(true, "secret-token"), null, null, null, null)
        );
        WebTestClient.bindToController(emergencyController)
                .build()
                .post()
                .uri("/internal/strategy/lfa/lifecycle")
                .header(LfaLifecycleOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .bodyValue(new LfaLifecycleOperatorController.LifecycleTransitionRequest(
                        "ACTIVE",
                        "operator",
                        "unsafe restart"
                ))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("bad_request")
                .jsonPath("$.message")
                .isEqualTo("lifecycle transition from EMERGENCY_STOP requires allowEmergencyStopReactivation=true");
    }

    @Test
    void rejects_draining_to_stopped_when_projection_still_has_open_exposure() {
        TradingStateProjection drainingProjection = new TradingStateProjection();
        drainingProjection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(openPosition()),
                List.of(openOrder()),
                List.of(),
                List.of()
        ));
        LfaSignalRunner drainingRunner = runner("DRAINING", drainingProjection, new NoopTradingEventBus());
        LfaLifecycleOperatorController drainingController = new LfaLifecycleOperatorController(
                drainingRunner,
                new InterventionProperties(new InterventionProperties.OperatorApi(true, "secret-token"), null, null, null, null)
        );

        WebTestClient.bindToController(drainingController)
                .build()
                .post()
                .uri("/internal/strategy/lfa/lifecycle")
                .header(LfaLifecycleOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .bodyValue(new LfaLifecycleOperatorController.LifecycleTransitionRequest(
                        "STOPPED",
                        "operator",
                        "drain complete"
                ))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("bad_request")
                .jsonPath("$.message")
                .isEqualTo("lifecycle transition DRAINING->STOPPED requires no open orders or positions");

        WebTestClient.bindToController(drainingController)
                .build()
                .get()
                .uri("/internal/strategy/lfa/lifecycle")
                .header(LfaLifecycleOperatorController.OPERATOR_TOKEN_HEADER, "secret-token")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.effectiveLifecycleState")
                .isEqualTo("DRAINING")
                .jsonPath("$.openOrderCount")
                .isEqualTo(1)
                .jsonPath("$.openPositionCount")
                .isEqualTo(1)
                .jsonPath("$.drainComplete")
                .isEqualTo(false);
    }

    private LfaSignalRunner runner(
            String lifecycleState,
            TradingStateProjection projection,
            TradingEventBus eventBus
    ) {
        return new LfaSignalRunner(
                new LfaMarketSignalAnalyzer(),
                properties(lifecycleState),
                projection,
                eventBus,
                new ExecutionProperties(new ExecutionProperties.SignalPlanner(true, null), null),
                null,
                null,
                java.time.Clock.systemUTC()
        );
    }

    private LfaStrategyProperties.SignalRunner properties(String lifecycleState) {
        return new LfaStrategyProperties.SignalRunner(
                true,
                30_000L,
                30_000L,
                30_000L,
                "lfa",
                "binance",
                "demo",
                "main",
                "usdm_futures",
                lifecycleState,
                List.of("ACTIVE"),
                Map.of(
                        "STARTING", List.of("PAUSED", "STOPPED", "EMERGENCY_STOP"),
                        "PAUSED", List.of("ACTIVE", "DRAINING", "STOPPED", "EMERGENCY_STOP"),
                        "ACTIVE", List.of("PAUSED", "DRAINING", "EMERGENCY_STOP"),
                        "DRAINING", List.of("PAUSED", "STOPPED", "EMERGENCY_STOP"),
                        "STOPPED", List.of("STARTING", "PAUSED", "EMERGENCY_STOP"),
                        "EMERGENCY_STOP", List.of("STOPPED")
                ),
                false,
                true,
                1,
                1,
                30_000L,
                true,
                null,
                new BigDecimal("1.50"),
                new BigDecimal("5"),
                new BigDecimal("250"),
                30_000L,
                "0.001",
                null,
                null,
                null,
                null,
                null,
                true,
                "EQUAL",
                new BigDecimal("100000000"),
                new BigDecimal("100000"),
                new BigDecimal("50000000"),
                BigDecimal.ONE,
                new BigDecimal("10"),
                null,
                null,
                null,
                1,
                null,
                null,
                null,
                null,
                3,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                true,
                true
        );
    }

    private TradingStateProjection.OrderState openOrder() {
        return new TradingStateProjection.OrderState(
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "BTCUSDT",
                "cmd-open",
                "client-open",
                "exchange-open",
                "ACCEPTED",
                "NEW",
                "BUY",
                "LIMIT",
                "50000.00",
                "0.001",
                "0",
                null,
                null,
                "ORDER_RESULT",
                "NEW",
                true,
                false,
                null,
                Instant.parse("2026-06-13T00:00:00Z"),
                "evt-order-open"
        );
    }

    private TradingStateProjection.PositionState openPosition() {
        return new TradingStateProjection.PositionState(
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "BTCUSDT",
                "BOTH",
                "ONE_WAY",
                "0.001",
                "50000.00",
                "50010.00",
                "0.01",
                "5",
                "cross",
                null,
                "POSITION_UPDATE",
                false,
                null,
                Instant.parse("2026-06-13T00:00:01Z"),
                "evt-position-open"
        );
    }

    private static final class NoopTradingEventBus implements TradingEventBus {

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
                    0
            ));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this test");
        }
    }
}
