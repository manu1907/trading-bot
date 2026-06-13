package io.github.manu.strategy.lfa;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.execution.ExecutionProperties;
import io.github.manu.intervention.InterventionProperties;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.List;
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
                java.time.Clock.systemUTC()
        );
    }

    private LfaStrategyProperties.SignalRunner properties(String lifecycleState) {
        return new LfaStrategyProperties.SignalRunner(
                true,
                30_000L,
                30_000L,
                "lfa",
                "binance",
                "demo",
                "main",
                "usdm_futures",
                lifecycleState,
                List.of("ACTIVE"),
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
                true,
                1,
                3,
                1,
                null,
                null,
                null,
                null,
                true,
                true
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
