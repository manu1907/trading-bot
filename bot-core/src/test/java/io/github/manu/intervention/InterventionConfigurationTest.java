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
                .hasSingleBean(InterventionRemediationExecutorService.class)
                .hasSingleBean(InterventionAutomatedDecisionService.class)
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

    @Test
    void binds_automated_decision_service_policy() {
        contextRunner
                .withPropertyValues(
                        "trading.intervention.automated-decision-service.enabled=true",
                        "trading.intervention.automated-decision-service.include-operator-review-actions=true",
                        "trading.intervention.automated-decision-service.max-decisions-per-run=7",
                        "trading.intervention.automated-decision-service.decided-by=auto-policy",
                        "trading.intervention.automated-decision-service.decision-reason=automated remediation batch"
                )
                .run(context -> {
                    InterventionProperties.AutomatedDecisionService service =
                            context.getBean(InterventionProperties.class).automatedDecisionService();
                    assertThat(service.enabled()).isTrue();
                    assertThat(service.includeOperatorReviewActions()).isTrue();
                    assertThat(service.maxDecisionsPerRun()).isEqualTo(7);
                    assertThat(service.decidedBy()).isEqualTo("auto-policy");
                    assertThat(service.decisionReason()).isEqualTo("automated remediation batch");
                });
    }

    @Test
    void keeps_remediation_executor_policy_non_executable_by_default() {
        contextRunner.run(context -> {
            InterventionProperties.RemediationExecutorPolicy policy =
                    context.getBean(InterventionProperties.class).remediationExecutorPolicy();

            assertThat(policy.enabled()).isFalse();
            assertThat(policy.exchangeExecutionEnabled()).isFalse();
            assertThat(policy.dryRunOnly()).isTrue();
            assertThat(policy.allowRealEnvironment()).isFalse();
            assertThat(policy.requireReadyPlan()).isTrue();
            assertThat(policy.requireFreshProjectionMatch()).isTrue();
            assertThat(policy.requireProjectionTargetIdentity()).isTrue();
            assertThat(policy.requireManagedExecutionPipeline()).isTrue();
            assertThat(policy.rejectStaleProjection()).isTrue();
            assertThat(policy.rejectUnsupportedPlans()).isTrue();
            assertThat(policy.rejectOperatorReviewPlans()).isTrue();
            assertThat(policy.rejectInsufficientDataPlans()).isTrue();
            assertThat(policy.maxPlansPerRun()).isEqualTo(25);
            assertThat(policy.allowedOperations()).isEmpty();
        });
    }

    @Test
    void binds_remediation_executor_policy_allowlist() {
        contextRunner
                .withPropertyValues(
                        "trading.intervention.remediation-executor-policy.enabled=true",
                        "trading.intervention.remediation-executor-policy.exchange-execution-enabled=true",
                        "trading.intervention.remediation-executor-policy.dry-run-only=false",
                        "trading.intervention.remediation-executor-policy.allow-real-environment=false",
                        "trading.intervention.remediation-executor-policy.max-plans-per-run=3",
                        "trading.intervention.remediation-executor-policy.allowed-operations[0]=CANCEL_ORDER",
                        "trading.intervention.remediation-executor-policy.allowed-operations[1]=PAUSE_SYMBOL"
                )
                .run(context -> {
                    InterventionProperties.RemediationExecutorPolicy policy =
                            context.getBean(InterventionProperties.class).remediationExecutorPolicy();

                    assertThat(policy.enabled()).isTrue();
                    assertThat(policy.exchangeExecutionEnabled()).isTrue();
                    assertThat(policy.dryRunOnly()).isFalse();
                    assertThat(policy.allowRealEnvironment()).isFalse();
                    assertThat(policy.maxPlansPerRun()).isEqualTo(3);
                    assertThat(policy.allowedOperations())
                            .containsExactly(
                                    InterventionProperties.ExecutableOperation.CANCEL_ORDER,
                                    InterventionProperties.ExecutableOperation.PAUSE_SYMBOL
                            );
                });
    }

    @Test
    void rejects_exchange_executor_policy_without_explicit_operations() {
        contextRunner
                .withPropertyValues(
                        "trading.intervention.remediation-executor-policy.enabled=true",
                        "trading.intervention.remediation-executor-policy.exchange-execution-enabled=true",
                        "trading.intervention.remediation-executor-policy.dry-run-only=false"
                )
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseMessage("exchangeExecutionEnabled requires at least one allowed operation"));
    }

    @Test
    void rejects_exchange_executor_policy_when_pipeline_gate_is_disabled() {
        contextRunner
                .withPropertyValues(
                        "trading.intervention.remediation-executor-policy.enabled=true",
                        "trading.intervention.remediation-executor-policy.exchange-execution-enabled=true",
                        "trading.intervention.remediation-executor-policy.dry-run-only=false",
                        "trading.intervention.remediation-executor-policy.require-managed-execution-pipeline=false",
                        "trading.intervention.remediation-executor-policy.allowed-operations[0]=CANCEL_ORDER"
                )
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseMessage("exchangeExecutionEnabled requires requireManagedExecutionPipeline=true"));
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
