package io.github.manu.intervention;

import io.github.manu.config.properties.ExchangeProperties;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class InterventionAutomatedRemediationRunnerTest {

    private static final Instant NOW = Instant.parse("2026-06-07T18:00:00Z");

    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
    private final TradingStateProjection projection = new TradingStateProjection();
    private final InterventionRemediationAdvisor advisor = new InterventionRemediationAdvisor(
            projection,
            new InterventionProperties.AutomatedPolicy(
                    InterventionProperties.RemediationAction.CLOSE,
                    null,
                    null,
                    null,
                    null
            )
    );
    private final InterventionRemediationCommandPlanner planner = new InterventionRemediationCommandPlanner(projection);

    @Test
    void executes_projected_decisions_before_publishing_new_decisions() {
        projection.restore(snapshot(
                List.of(order("client-1", "evt-order-intervention")),
                List.of(existingDecision("client-1", "evt-order-intervention"))
        ));
        InterventionAutomatedRemediationRunner runner = runner(
                runnerProperties(true, true, explicitTarget()),
                executorService(executorPreviewPolicy()),
                automatedDecisionService()
        );

        InterventionAutomatedRemediationRunner.AutomatedRemediationRunResult result = runner.runOnce();

        assertThat(result.enabled()).isTrue();
        assertThat(result.reason()).isEqualTo("runner:completed");
        assertThat(result.executionBatch().evaluatedCount()).isEqualTo(1);
        assertThat(result.executionBatch().previewOnlyCount()).isEqualTo(1);
        assertThat(result.decisionBatch().publishedCount()).isZero();
        assertThat(result.decisionBatch().skippedCount()).isEqualTo(1);
        assertThat(eventBus.envelopes).isEmpty();
    }

    @Test
    void publishes_new_automated_decisions_for_the_next_runner_tick() {
        projection.restore(snapshot(
                List.of(order("client-1", "evt-order-intervention")),
                List.of()
        ));
        InterventionAutomatedRemediationRunner runner = runner(
                runnerProperties(true, true, explicitTarget()),
                executorService(executorPreviewPolicy()),
                automatedDecisionService()
        );

        InterventionAutomatedRemediationRunner.AutomatedRemediationRunResult result = runner.runOnce();

        assertThat(result.executionBatch().evaluatedCount()).isZero();
        assertThat(result.decisionBatch().publishedCount()).isEqualTo(1);
        assertThat(eventBus.envelopes).singleElement().satisfies(envelope -> {
            assertThat(envelope.eventType()).isEqualTo(TradingEventType.REMEDIATION_DECISION);
            RemediationDecisionEvent event = (RemediationDecisionEvent) envelope.value();
            assertThat(event.getAction()).hasToString("CLOSE");
            assertThat(event.getClientOrderId()).hasToString("client-1");
            assertThat(event.getAttributes()).containsEntry("recommendation_event_id", "evt-order-intervention");
        });
    }

    @Test
    void can_resolve_target_from_active_runtime_config() {
        projection.restore(snapshot(
                List.of(order("client-1", "evt-order-intervention")),
                List.of()
        ));
        InterventionAutomatedRemediationRunner runner = runner(
                runnerProperties(true, false, activeTarget()),
                executorService(executorPreviewPolicy()),
                automatedDecisionService(),
                () -> new ExchangeProperties("binance", "demo", "main", "usd_m_futures")
        );

        InterventionAutomatedRemediationRunner.AutomatedRemediationRunResult result = runner.runOnce();

        assertThat(result.target()).isEqualTo(explicitTarget());
        assertThat(result.decisionBatch().publishedCount()).isEqualTo(1);
        assertThat(result.executionBatch().enabled()).isFalse();
    }

    private InterventionAutomatedRemediationRunner runner(
            InterventionProperties.AutomatedRemediationRunner properties,
            InterventionRemediationExecutorService executorService,
            InterventionAutomatedDecisionService automatedDecisionService
    ) {
        return runner(properties, executorService, automatedDecisionService, () -> null);
    }

    private InterventionAutomatedRemediationRunner runner(
            InterventionProperties.AutomatedRemediationRunner properties,
            InterventionRemediationExecutorService executorService,
            InterventionAutomatedDecisionService automatedDecisionService,
            java.util.function.Supplier<ExchangeProperties> activeTargetSupplier
    ) {
        return new InterventionAutomatedRemediationRunner(
                automatedDecisionService,
                executorService,
                properties,
                activeTargetSupplier
        );
    }

    private InterventionAutomatedDecisionService automatedDecisionService() {
        return new InterventionAutomatedDecisionService(
                eventBus,
                advisor,
                projection,
                new InterventionProperties.AutomatedDecisionService(true, false, 10, null, null),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "decision-1"
        );
    }

    private InterventionRemediationExecutorService executorService(
            InterventionProperties.RemediationExecutorPolicy policy
    ) {
        return new InterventionRemediationExecutorService(
                projection,
                planner,
                new InterventionProperties(null, null, null, null, policy)
        );
    }

    private InterventionProperties.AutomatedRemediationRunner runnerProperties(
            boolean publishDecisions,
            boolean executeRemediation,
            InterventionProperties.Target target
    ) {
        return new InterventionProperties.AutomatedRemediationRunner(
                true,
                1_000L,
                0L,
                publishDecisions,
                executeRemediation,
                target
        );
    }

    private InterventionProperties.Target explicitTarget() {
        return new InterventionProperties.Target("binance", "demo", "main", "usd_m_futures");
    }

    private InterventionProperties.Target activeTarget() {
        return new InterventionProperties.Target(null, null, null, null);
    }

    private InterventionProperties.RemediationExecutorPolicy executorPreviewPolicy() {
        return new InterventionProperties.RemediationExecutorPolicy(
                true,
                false,
                true,
                false,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                25,
                List.of(),
                InterventionProperties.PositionOrderPolicy.disabled()
        );
    }

    private TradingStateSnapshot snapshot(
            List<TradingStateProjection.OrderState> orders,
            List<TradingStateProjection.RemediationDecisionState> decisions
    ) {
        return new TradingStateSnapshot(
                List.of(),
                List.of(),
                orders,
                List.of(),
                List.of(),
                decisions,
                List.of(),
                decisions.stream().map(TradingStateProjection.RemediationDecisionState::eventId).toList()
        );
    }

    private TradingStateProjection.OrderState order(String clientOrderId, String eventId) {
        return new TradingStateProjection.OrderState(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                "BTCUSDT",
                null,
                clientOrderId,
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
                eventId
        );
    }

    private TradingStateProjection.RemediationDecisionState existingDecision(
            String clientOrderId,
            String recommendationEventId
    ) {
        return new TradingStateProjection.RemediationDecisionState(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                "BTCUSDT",
                "remediation-1",
                "ORDER",
                "CLOSE",
                clientOrderId,
                null,
                "external_order_observed",
                List.of("intervention:external_order_observed"),
                "automated_remediation_policy",
                "automated policy selected remediation action",
                Map.of("recommendation_event_id", recommendationEventId),
                NOW,
                "evt-remediation-1"
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
