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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InterventionAutomatedDecisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-06T17:00:00Z");

    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
    private final TradingStateProjection projection = new TradingStateProjection();

    @Test
    void publishes_automated_decisions_for_non_review_policy_recommendations() {
        restoreOrderAndOpenPositionInterventions();
        InterventionAutomatedDecisionService service = service(
                new InterventionRemediationAdvisor(
                        projection,
                        new InterventionProperties.AutomatedPolicy(
                                InterventionProperties.RemediationAction.ADOPT,
                                InterventionProperties.RemediationAction.AMEND,
                                InterventionProperties.RemediationAction.IGNORE,
                                InterventionProperties.RemediationAction.HEDGE,
                                InterventionProperties.RemediationAction.PAUSE_SYMBOL
                        )
                ),
                enabledProperties(false, 10)
        );

        InterventionAutomatedDecisionService.AutomatedDecisionBatch batch =
                service.decide("binance", "demo", "main", "usd_m_futures").join();

        assertThat(batch.enabled()).isTrue();
        assertThat(batch.publishedCount()).isEqualTo(2);
        assertThat(batch.skippedCount()).isZero();
        assertThat(eventBus.envelopes).hasSize(2);
        assertThat(eventBus.envelopes).extracting(TradingEventEnvelope::eventType)
                .containsOnly(TradingEventType.REMEDIATION_DECISION);
        assertThat(eventBus.envelopes)
                .extracting(envelope -> ((RemediationDecisionEvent) envelope.value()).getAction().toString())
                .containsExactly("ADOPT", "HEDGE");
        RemediationDecisionEvent orderDecision = (RemediationDecisionEvent) eventBus.envelopes.getFirst().value();
        assertThat(orderDecision.getRemediationId()).hasToString("remediation:auto:decision-1");
        assertThat(orderDecision.getDecidedBy()).hasToString("automated_remediation_policy");
        assertThat(orderDecision.getDecisionReason()).hasToString("automated policy selected remediation action");
        assertThat(orderDecision.getAttributes())
                .containsEntry("recommendation_event_id", "evt-order-intervention")
                .containsEntry("automated_decision_service", "true");
    }

    @Test
    void skips_operator_review_recommendations_by_default() {
        restoreOrderIntervention();
        InterventionAutomatedDecisionService service = service(
                new InterventionRemediationAdvisor(projection),
                enabledProperties(false, 10)
        );

        InterventionAutomatedDecisionService.AutomatedDecisionBatch batch =
                service.decide("binance", "demo", "main", "usd_m_futures").join();

        assertThat(batch.publishedCount()).isZero();
        assertThat(batch.skippedCount()).isEqualTo(1);
        assertThat(batch.outcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.status()).isEqualTo(InterventionAutomatedDecisionService.AutomatedDecisionStatus.SKIPPED);
            assertThat(outcome.reason()).isEqualTo("automated_decision:operator_review_disabled");
        });
        assertThat(eventBus.envelopes).isEmpty();
    }

    @Test
    void can_include_operator_review_when_explicitly_configured() {
        restoreOrderIntervention();
        InterventionAutomatedDecisionService service = service(
                new InterventionRemediationAdvisor(projection),
                enabledProperties(true, 10)
        );

        InterventionAutomatedDecisionService.AutomatedDecisionBatch batch =
                service.decide("binance", "demo", "main", "usd_m_futures").join();

        assertThat(batch.publishedCount()).isEqualTo(1);
        assertThat(eventBus.envelopes).singleElement().satisfies(envelope -> {
            RemediationDecisionEvent event = (RemediationDecisionEvent) envelope.value();
            assertThat(event.getAction()).hasToString("OPERATOR_REVIEW");
            assertThat(event.getAttributes()).containsEntry("recommendation_event_id", "evt-order-intervention");
        });
    }

    @Test
    void skips_recommendations_that_already_have_a_remediation_decision() {
        restoreOrderInterventionWithExistingDecision();
        InterventionAutomatedDecisionService service = service(
                new InterventionRemediationAdvisor(
                        projection,
                        new InterventionProperties.AutomatedPolicy(
                                InterventionProperties.RemediationAction.CLOSE,
                                null,
                                null,
                                null,
                                null
                        )
                ),
                enabledProperties(false, 10)
        );

        InterventionAutomatedDecisionService.AutomatedDecisionBatch batch =
                service.decide("binance", "demo", "main", "usd_m_futures").join();

        assertThat(batch.publishedCount()).isZero();
        assertThat(batch.skippedCount()).isEqualTo(1);
        assertThat(batch.outcomes()).singleElement().satisfies(outcome ->
                assertThat(outcome.reason()).isEqualTo("automated_decision:duplicate_recommendation"));
        assertThat(eventBus.envelopes).isEmpty();
    }

    @Test
    void remains_inert_when_disabled() {
        restoreOrderIntervention();
        InterventionAutomatedDecisionService service = service(
                new InterventionRemediationAdvisor(projection),
                new InterventionProperties.AutomatedDecisionService(false, false, 10, null, null)
        );

        InterventionAutomatedDecisionService.AutomatedDecisionBatch batch =
                service.decide("binance", "demo", "main", "usd_m_futures").join();

        assertThat(batch.enabled()).isFalse();
        assertThat(batch.outcomes()).isEmpty();
        assertThat(eventBus.envelopes).isEmpty();
    }

    private InterventionAutomatedDecisionService service(
            InterventionRemediationAdvisor advisor,
            InterventionProperties.AutomatedDecisionService properties
    ) {
        AtomicInteger id = new AtomicInteger(1);
        return new InterventionAutomatedDecisionService(
                eventBus,
                advisor,
                projection,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "decision-" + id.getAndIncrement()
        );
    }

    private InterventionProperties.AutomatedDecisionService enabledProperties(
            boolean includeOperatorReviewActions,
            int maxDecisionsPerRun
    ) {
        return new InterventionProperties.AutomatedDecisionService(
                true,
                includeOperatorReviewActions,
                maxDecisionsPerRun,
                null,
                null
        );
    }

    private void restoreOrderAndOpenPositionInterventions() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(position("ETHUSDT", "BOTH", "0.25", true)),
                List.of(order("BTCUSDT", "client-1", true)),
                List.of(),
                List.of()
        ));
    }

    private void restoreOrderIntervention() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(order("BTCUSDT", "client-1", true)),
                List.of(),
                List.of()
        ));
    }

    private void restoreOrderInterventionWithExistingDecision() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(order("BTCUSDT", "client-1", true)),
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.RemediationDecisionState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "remediation-1",
                        "ORDER",
                        "CLOSE",
                        "client-1",
                        null,
                        "external_order_observed",
                        List.of("intervention:external_order_observed"),
                        "automated_remediation_policy",
                        "automated policy selected remediation action",
                        Map.of("recommendation_event_id", "evt-order-intervention"),
                        NOW.minusSeconds(1),
                        "evt-remediation-decision"
                )),
                List.of("evt-remediation-decision")
        ));
    }

    private TradingStateProjection.OrderState order(
            String symbol,
            String clientOrderId,
            boolean externalIntervention
    ) {
        return new TradingStateProjection.OrderState(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                symbol,
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
                externalIntervention,
                "external_order_observed",
                NOW.minusSeconds(1),
                "evt-order-intervention"
        );
    }

    private TradingStateProjection.PositionState position(
            String symbol,
            String positionSide,
            String positionAmount,
            boolean externalIntervention
    ) {
        return new TradingStateProjection.PositionState(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                symbol,
                positionSide,
                positionAmount,
                "50000.00",
                "50010.00",
                "12.50",
                "USER_DATA",
                externalIntervention,
                "external_position_change",
                NOW.minusSeconds(1),
                "evt-position-intervention"
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
