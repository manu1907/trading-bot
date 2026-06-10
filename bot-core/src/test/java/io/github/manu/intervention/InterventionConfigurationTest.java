package io.github.manu.intervention;

import io.github.manu.audit.AuditLogger;
import io.github.manu.config.runtime.ConfigManager;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventHandlerRegistration;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.observability.PauseGovernanceMetrics;
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
            .withBean(AuditLogger.class, AuditLogger::new)
            .withBean(PauseGovernanceMetrics.class, PauseGovernanceMetrics::new)
            .withBean(TradingStateProjection.class, TradingStateProjection::new);

    @Test
    void keeps_operator_api_disabled_by_default() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(InterventionProperties.class)
                .hasSingleBean(InterventionAcknowledgementService.class)
                .hasSingleBean(InterventionRemediationCommandPlanner.class)
                .hasSingleBean(InterventionRemediationExecutorService.class)
                .hasSingleBean(InterventionAutomatedDecisionService.class)
                .doesNotHaveBean(InterventionAutomatedRemediationRunner.class)
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
    void binds_automated_remediation_runner_policy() {
        contextRunner
                .withPropertyValues(
                        "trading.intervention.automated-remediation-runner.enabled=true",
                        "trading.intervention.automated-remediation-runner.interval-millis=5000",
                        "trading.intervention.automated-remediation-runner.initial-delay-millis=250",
                        "trading.intervention.automated-remediation-runner.publish-decisions=false",
                        "trading.intervention.automated-remediation-runner.execute-remediation=true",
                        "trading.intervention.automated-remediation-runner.target.provider=binance",
                        "trading.intervention.automated-remediation-runner.target.environment=demo",
                        "trading.intervention.automated-remediation-runner.target.account=main",
                        "trading.intervention.automated-remediation-runner.target.market=usdm_futures"
                )
                .withBean(ConfigManager.class, ConfigManager::new)
                .run(context -> {
                    InterventionProperties.AutomatedRemediationRunner runner =
                            context.getBean(InterventionProperties.class).automatedRemediationRunner();

                    assertThat(runner.enabled()).isTrue();
                    assertThat(runner.intervalMillis()).isEqualTo(5000L);
                    assertThat(runner.initialDelayMillis()).isEqualTo(250L);
                    assertThat(runner.publishDecisions()).isFalse();
                    assertThat(runner.executeRemediation()).isTrue();
                    assertThat(runner.target().provider()).isEqualTo("binance");
                    assertThat(runner.target().environment()).isEqualTo("demo");
                    assertThat(runner.target().account()).isEqualTo("main");
                    assertThat(runner.target().market()).isEqualTo("usdm_futures");
                    assertThat(context).hasSingleBean(InterventionAutomatedRemediationRunner.class);
                });
    }

    @Test
    void rejects_automated_remediation_runner_without_work_when_enabled() {
        contextRunner
                .withPropertyValues(
                        "trading.intervention.automated-remediation-runner.enabled=true",
                        "trading.intervention.automated-remediation-runner.publish-decisions=false",
                        "trading.intervention.automated-remediation-runner.execute-remediation=false"
                )
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseMessage(
                                "automatedRemediationRunner requires publishDecisions or executeRemediation when enabled"
                        ));
    }

    @Test
    void keeps_remediation_executor_policy_non_executable_by_default() {
        contextRunner.run(context -> {
            InterventionProperties.RemediationExecutorPolicy policy =
                    context.getBean(InterventionProperties.class).remediationExecutorPolicy();

            assertThat(policy.enabled()).isFalse();
            assertThat(policy.exchangeExecutionEnabled()).isFalse();
            assertThat(policy.reportOnly()).isTrue();
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
            assertThat(policy.positionOrderPolicy().oneWayReduceOnlyEnabled()).isFalse();
            assertThat(policy.positionOrderPolicy().provider()).isEqualTo("binance");
            assertThat(policy.positionOrderPolicy().market()).isEqualTo("usdm_futures");
            assertThat(policy.positionOrderPolicy().positionSide()).isEqualTo("BOTH");
            assertThat(policy.positionOrderPolicy().orderType()).isEqualTo("MARKET");
            assertThat(policy.positionOrderPolicy().requireReduceOnly()).isTrue();
            assertThat(policy.positionOrderPolicy().requireClosePositionFalse()).isTrue();
            assertThat(policy.positionOrderPolicy().hedgeModeExecutionEnabled()).isFalse();
            assertThat(policy.positionOrderPolicy().hedgePositionOrderEnabled()).isFalse();
            assertThat(policy.positionOrderPolicy().allowedSymbols()).isEmpty();
            assertThat(policy.positionOrderPolicy().maxPositionQuantity()).isNull();
            assertThat(policy.positionOrderPolicy().chunkCloseWhenMaxQuantityExceeded()).isFalse();
            assertThat(policy.positionOrderPolicy().maxPositionNotional()).isNull();
            assertThat(policy.positionOrderPolicy().rejectUnboundedPositionNotional()).isTrue();
            assertThat(policy.positionOrderPolicy().requiredMarginType()).isNull();
            assertThat(policy.positionOrderPolicy().minLeverage()).isNull();
            assertThat(policy.positionOrderPolicy().maxLeverage()).isNull();
            assertThat(policy.positionOrderPolicy().rejectMissingAccountRiskMetadata()).isTrue();
        });
    }

    @Test
    void binds_remediation_executor_policy_allowlist() {
        contextRunner
                .withPropertyValues(
                        "trading.intervention.remediation-executor-policy.enabled=true",
                        "trading.intervention.remediation-executor-policy.exchange-execution-enabled=true",
                        "trading.intervention.remediation-executor-policy.report-only=false",
                        "trading.intervention.remediation-executor-policy.allow-real-environment=false",
                        "trading.intervention.remediation-executor-policy.max-plans-per-run=3",
                        "trading.intervention.remediation-executor-policy.allowed-operations[0]=CANCEL_ORDER",
                        "trading.intervention.remediation-executor-policy.allowed-operations[1]=PAUSE_SYMBOL",
                        "trading.intervention.remediation-executor-policy.position-order-policy.one-way-reduce-only-enabled=true",
                        "trading.intervention.remediation-executor-policy.position-order-policy.provider=binance",
                        "trading.intervention.remediation-executor-policy.position-order-policy.market=usdm_futures",
                        "trading.intervention.remediation-executor-policy.position-order-policy.position-side=BOTH",
                        "trading.intervention.remediation-executor-policy.position-order-policy.order-type=MARKET",
                        "trading.intervention.remediation-executor-policy.position-order-policy.hedge-position-order-enabled=true",
                        "trading.intervention.remediation-executor-policy.position-order-policy.allowed-symbols[0]=btcusdt",
                        "trading.intervention.remediation-executor-policy.position-order-policy.max-position-quantity=0.001",
                        "trading.intervention.remediation-executor-policy.position-order-policy.chunk-close-when-max-quantity-exceeded=true",
                        "trading.intervention.remediation-executor-policy.position-order-policy.max-position-notional=250",
                        "trading.intervention.remediation-executor-policy.position-order-policy.reject-unbounded-position-notional=false",
                        "trading.intervention.remediation-executor-policy.position-order-policy.required-margin-type=cross",
                        "trading.intervention.remediation-executor-policy.position-order-policy.required-position-mode=hedge",
                        "trading.intervention.remediation-executor-policy.position-order-policy.min-leverage=1",
                        "trading.intervention.remediation-executor-policy.position-order-policy.max-leverage=5",
                        "trading.intervention.remediation-executor-policy.position-order-policy.max-account-position-notional=1000",
                        "trading.intervention.remediation-executor-policy.position-order-policy.max-symbol-position-notional=500",
                        "trading.intervention.remediation-executor-policy.position-order-policy.max-account-unrealized-loss=250",
                        "trading.intervention.remediation-executor-policy.position-order-policy.max-symbol-unrealized-loss=125",
                        "trading.intervention.remediation-executor-policy.position-order-policy.max-account-margin-utilization=0.80",
                        "trading.intervention.remediation-executor-policy.position-order-policy.reject-missing-account-risk-metadata=false"
                )
                .run(context -> {
                    InterventionProperties.RemediationExecutorPolicy policy =
                            context.getBean(InterventionProperties.class).remediationExecutorPolicy();

                    assertThat(policy.enabled()).isTrue();
                    assertThat(policy.exchangeExecutionEnabled()).isTrue();
                    assertThat(policy.reportOnly()).isFalse();
                    assertThat(policy.allowRealEnvironment()).isFalse();
                    assertThat(policy.maxPlansPerRun()).isEqualTo(3);
                    assertThat(policy.allowedOperations())
                            .containsExactly(
                                    InterventionProperties.ExecutableOperation.CANCEL_ORDER,
                                    InterventionProperties.ExecutableOperation.PAUSE_SYMBOL
                            );
                    assertThat(policy.positionOrderPolicy().oneWayReduceOnlyEnabled()).isTrue();
                    assertThat(policy.positionOrderPolicy().provider()).isEqualTo("binance");
                    assertThat(policy.positionOrderPolicy().market()).isEqualTo("usdm_futures");
                    assertThat(policy.positionOrderPolicy().positionSide()).isEqualTo("BOTH");
                    assertThat(policy.positionOrderPolicy().orderType()).isEqualTo("MARKET");
                    assertThat(policy.positionOrderPolicy().hedgePositionOrderEnabled()).isTrue();
                    assertThat(policy.positionOrderPolicy().allowedSymbols()).containsExactly("BTCUSDT");
                    assertThat(policy.positionOrderPolicy().maxPositionQuantity()).isEqualTo("0.001");
                    assertThat(policy.positionOrderPolicy().chunkCloseWhenMaxQuantityExceeded()).isTrue();
                    assertThat(policy.positionOrderPolicy().maxPositionNotional()).isEqualTo("250");
                    assertThat(policy.positionOrderPolicy().rejectUnboundedPositionNotional()).isFalse();
                    assertThat(policy.positionOrderPolicy().requiredMarginType()).isEqualTo("CROSS");
                    assertThat(policy.positionOrderPolicy().requiredPositionMode()).isEqualTo("HEDGE");
                    assertThat(policy.positionOrderPolicy().minLeverage()).isEqualTo("1");
                    assertThat(policy.positionOrderPolicy().maxLeverage()).isEqualTo("5");
                    assertThat(policy.positionOrderPolicy().maxAccountPositionNotional()).isEqualTo("1000");
                    assertThat(policy.positionOrderPolicy().maxSymbolPositionNotional()).isEqualTo("500");
                    assertThat(policy.positionOrderPolicy().maxAccountUnrealizedLoss()).isEqualTo("250");
                    assertThat(policy.positionOrderPolicy().maxSymbolUnrealizedLoss()).isEqualTo("125");
                    assertThat(policy.positionOrderPolicy().maxAccountMarginUtilization()).isEqualTo("0.80");
                    assertThat(policy.positionOrderPolicy().rejectMissingAccountRiskMetadata()).isFalse();
                });
    }

    @Test
    void rejects_exchange_executor_policy_without_explicit_operations() {
        contextRunner
                .withPropertyValues(
                        "trading.intervention.remediation-executor-policy.enabled=true",
                        "trading.intervention.remediation-executor-policy.exchange-execution-enabled=true",
                        "trading.intervention.remediation-executor-policy.report-only=false"
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
                        "trading.intervention.remediation-executor-policy.report-only=false",
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
