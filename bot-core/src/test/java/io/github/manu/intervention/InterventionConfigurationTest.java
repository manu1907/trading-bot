package io.github.manu.intervention;

import io.github.manu.events.TradingEventType;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventHandlerRegistration;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class InterventionConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(InterventionConfiguration.class, InterventionOperatorController.class)
            .withBean(TradingEventBus.class, NoopTradingEventBus::new)
            .withBean(TradingStateProjection.class, TradingStateProjection::new);

    @Test
    void keeps_operator_api_disabled_by_default() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(InterventionProperties.class)
                .hasSingleBean(InterventionAcknowledgementService.class)
                .hasSingleBean(InterventionRemediationCommandPlanner.class)
                .doesNotHaveBean(InterventionRemediationOrchestrator.class)
                .doesNotHaveBean(InterventionOperatorController.class));
    }

    @Test
    void creates_operator_api_when_enabled_with_token() {
        contextRunner
                .withPropertyValues(
                        "trading.intervention.operator-api.enabled=true",
                        "trading.intervention.operator-api.operator-token=secret-token"
                )
                .run(context -> assertThat(context)
                        .hasSingleBean(InterventionAcknowledgementService.class)
                        .hasSingleBean(InterventionOperatorController.class));
    }

    @Test
    void fails_operator_api_fast_when_enabled_without_token() {
        contextRunner
                .withPropertyValues("trading.intervention.operator-api.enabled=true")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseMessage("operatorToken is required"));
    }

    @Test
    void creates_live_only_remediation_orchestrator_when_enabled() {
        contextRunner
                .withPropertyValues("trading.intervention.remediation-orchestrator.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(InterventionRemediationOrchestrator.class);
                    TradingEventHandlerRegistration registration =
                            context.getBean("interventionRemediationOrchestratorHandler", TradingEventHandlerRegistration.class);
                    assertThat(registration.eventType()).isEqualTo(TradingEventType.REMEDIATION_DECISION);
                    assertThat(registration.live()).isTrue();
                    assertThat(registration.replay()).isFalse();
                });
    }

    @Test
    void binds_automated_remediation_policy_actions() {
        contextRunner
                .withPropertyValues(
                        "trading.intervention.automated-policy.external-order-action=ADOPT",
                        "trading.intervention.automated-policy.managed-order-change-action=AMEND",
                        "trading.intervention.automated-policy.flat-position-action=IGNORE",
                        "trading.intervention.automated-policy.open-position-action=HEDGE",
                        "trading.intervention.automated-policy.unknown-position-action=PAUSE_SYMBOL"
                )
                .run(context -> {
                    InterventionProperties.AutomatedPolicy policy =
                            context.getBean(InterventionProperties.class).automatedPolicy();
                    assertThat(policy.externalOrderAction()).isEqualTo(InterventionProperties.RemediationAction.ADOPT);
                    assertThat(policy.managedOrderChangeAction()).isEqualTo(InterventionProperties.RemediationAction.AMEND);
                    assertThat(policy.flatPositionAction()).isEqualTo(InterventionProperties.RemediationAction.IGNORE);
                    assertThat(policy.openPositionAction()).isEqualTo(InterventionProperties.RemediationAction.HEDGE);
                    assertThat(policy.unknownPositionAction())
                            .isEqualTo(InterventionProperties.RemediationAction.PAUSE_SYMBOL);
                });
    }

    private static final class NoopTradingEventBus implements TradingEventBus {

        @Override
        public CompletableFuture<PublishedTradingEvent> publish(
                TradingEventEnvelope<? extends SpecificRecord> envelope
        ) {
            throw new UnsupportedOperationException("publish is not used by this test");
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this test");
        }
    }
}
