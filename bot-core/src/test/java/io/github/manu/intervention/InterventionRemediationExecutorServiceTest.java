package io.github.manu.intervention;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.OrderResultStatus;
import io.github.manu.events.v1.RiskDecision;
import io.github.manu.events.v1.RiskDecisionEvent;
import io.github.manu.execution.ExecutionProperties;
import io.github.manu.execution.OrderExecutionGateway;
import io.github.manu.execution.OrderExecutionPipeline;
import io.github.manu.execution.OrderRiskGate;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import io.github.manu.reconciliation.ReconciliationConfidenceStatus;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import io.github.manu.reconciliation.ReconciliationObservation;
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

class InterventionRemediationExecutorServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-07T09:30:00Z");

    private final TradingStateProjection projection = new TradingStateProjection();
    private final InterventionRemediationCommandPlanner commandPlanner =
            new InterventionRemediationCommandPlanner(projection, enabledPositionOrderPolicy());

    @Test
    void returns_disabled_batch_when_executor_policy_is_disabled() {
        restoreCloseRemediationDecisionWithOrder("client-1", "remediation-1", "evt-remediation-1");

        InterventionRemediationExecutorService.RemediationExecutionBatch batch =
                service(policy(false, false, true, 25)).preview("binance", "demo", "main", "usd_m_futures");

        assertThat(batch.enabled()).isFalse();
        assertThat(batch.evaluatedCount()).isZero();
        assertThat(batch.reports()).isEmpty();
    }

    @Test
    void previews_executable_cancel_plan_when_exchange_execution_is_disabled() {
        restoreCloseRemediationDecisionWithOrder("client-1", "remediation-1", "evt-remediation-1");

        InterventionRemediationExecutorService.RemediationExecutionBatch batch =
                service(policy(true, false, true, 25)).preview("binance", "demo", "main", "usd_m_futures");

        assertThat(batch.enabled()).isTrue();
        assertThat(batch.exchangeExecutionEnabled()).isFalse();
        assertThat(batch.reportOnly()).isTrue();
        assertThat(batch.evaluatedCount()).isEqualTo(1);
        assertThat(batch.blockedCount()).isZero();
        assertThat(batch.previewOnlyCount()).isEqualTo(1);
        assertThat(batch.submittedCount()).isZero();
        assertThat(batch.noActionCount()).isZero();
        assertThat(batch.reports()).singleElement().satisfies(report -> {
            assertThat(report.remediationId()).isEqualTo("remediation-1");
            assertThat(report.planStatus()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
            assertThat(report.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CANCEL_ORDER);
            assertThat(report.status()).isEqualTo(InterventionRemediationExecutorService.ExecutionStatus.PREVIEW_ONLY);
            assertThat(report.exchangeExecutable()).isTrue();
            assertThat(report.reasons())
                    .containsExactly("remediation:cancel_external_order", "executor:exchange_execution_disabled");
            assertThat(report.attributes())
                    .containsEntry("executor_status", "PREVIEW_ONLY")
                    .containsEntry("executor_reason", "executor:exchange_execution_disabled")
                    .containsEntry("executor_exchange_execution_enabled", "false");
        });
    }

    @Test
    void reports_no_action_for_flat_position_close() {
        restoreFlatPositionCloseDecision();

        InterventionRemediationExecutorService.RemediationExecutionBatch batch =
                service(policy(true, false, true, 25)).preview("binance", "demo", "main", "usd_m_futures");

        assertThat(batch.evaluatedCount()).isEqualTo(1);
        assertThat(batch.blockedCount()).isZero();
        assertThat(batch.noActionCount()).isEqualTo(1);
        assertThat(batch.reports()).singleElement().satisfies(report -> {
            assertThat(report.status()).isEqualTo(InterventionRemediationExecutorService.ExecutionStatus.NO_ACTION);
            assertThat(report.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CLOSE_POSITION);
            assertThat(report.reasons())
                    .containsExactly("projection:position_already_flat", "executor:no_action_plan");
        });
    }

    @Test
    void limits_evaluation_to_policy_max_plans_per_run() {
        restoreTwoCloseRemediationDecisionsWithOrders();

        InterventionRemediationExecutorService.RemediationExecutionBatch batch =
                service(policy(true, false, true, 1)).preview("binance", "demo", "main", "usd_m_futures");

        assertThat(batch.evaluatedCount()).isEqualTo(1);
        assertThat(batch.reports()).hasSize(1);
    }

    @Test
    void submits_order_close_remediation_cancel_through_execution_pipeline_when_policy_allows() {
        restoreCloseRemediationDecisionWithOrder("client-1", "remediation-1", "evt-remediation-1");
        CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
        ReconciliationConfidenceTracker reconciliationTracker =
                new ReconciliationConfidenceTracker(Clock.fixed(NOW, ZoneOffset.UTC));
        reconciliationTracker.record(new ReconciliationObservation(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                TradingEventType.ORDER_RESULT,
                "binance|demo|main|usd_m_futures|BTCUSDT|client-1",
                ReconciliationConfidenceStatus.CONFIDENT,
                List.of()
        ));
        OrderExecutionPipeline pipeline = new OrderExecutionPipeline(
                new OrderRiskGate(new ExecutionProperties(null), reconciliationTracker, projection),
                eventBus,
                List.of(new CancelGateway())
        );

        InterventionRemediationExecutorService.RemediationExecutionBatch batch =
                service(liveCancelPolicy(), pipeline).execute("binance", "demo", "main", "usd_m_futures");

        assertThat(batch.evaluatedCount()).isEqualTo(1);
        assertThat(batch.blockedCount()).isZero();
        assertThat(batch.submittedCount()).isEqualTo(1);
        assertThat(batch.reports()).singleElement().satisfies(report -> {
            assertThat(report.status())
                    .isEqualTo(InterventionRemediationExecutorService.ExecutionStatus.SUBMITTED_TO_PIPELINE);
            assertThat(report.reasons())
                    .containsExactly(
                            "remediation:cancel_external_order",
                            "executor:submitted_to_order_execution_pipeline"
                    );
            assertThat(report.attributes())
                    .containsEntry("executor_submitted_command_id", "remediation-command:remediation-1:cancel-order")
                    .containsEntry("executor_submitted_target_client_order_id", "client-1")
                    .containsEntry("executor_submitted_target_exchange_order_id", "exchange-client-1");
        });
        assertThat(eventBus.values(RiskDecisionEvent.class))
                .singleElement()
                .extracting(RiskDecisionEvent::getDecision)
                .isEqualTo(RiskDecision.APPROVED);
        assertThat(eventBus.values(OrderResultEvent.class))
                .singleElement()
                .extracting(OrderResultEvent::getStatus)
                .isEqualTo(OrderResultStatus.CANCELED);
    }

    @Test
    void submits_position_close_remediation_as_reduce_only_market_order_through_execution_pipeline_when_policy_allows() {
        restorePositionCloseRemediationDecision("0.25", "CLOSE", Map.of());
        CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
        ReconciliationConfidenceTracker reconciliationTracker =
                new ReconciliationConfidenceTracker(Clock.fixed(NOW, ZoneOffset.UTC));
        reconciliationTracker.record(new ReconciliationObservation(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                TradingEventType.ORDER_RESULT,
                "binance|demo|main|usd_m_futures|BTCUSDT",
                ReconciliationConfidenceStatus.CONFIDENT,
                List.of()
        ));
        OrderExecutionPipeline pipeline = new OrderExecutionPipeline(
                new OrderRiskGate(new ExecutionProperties(null), reconciliationTracker, projection),
                eventBus,
                List.of(new PositionOrderGateway())
        );

        InterventionRemediationExecutorService.RemediationExecutionBatch batch =
                service(livePositionPolicy(InterventionProperties.ExecutableOperation.CLOSE_POSITION), pipeline)
                        .execute("binance", "demo", "main", "usd_m_futures");

        assertThat(batch.evaluatedCount()).isEqualTo(1);
        assertThat(batch.blockedCount()).isZero();
        assertThat(batch.submittedCount()).isEqualTo(1);
        assertThat(batch.reports()).singleElement().satisfies(report -> {
            assertThat(report.status())
                    .isEqualTo(InterventionRemediationExecutorService.ExecutionStatus.SUBMITTED_TO_PIPELINE);
            assertThat(report.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CLOSE_POSITION);
            assertThat(report.attributes())
                    .containsEntry("executor_submitted_command_id",
                            "remediation-command:remediation-position-1:close-position")
                    .containsEntry("executor_submitted_client_order_id", "rm-pos-remediation-position-1")
                    .containsEntry("executor_submitted_side", "SELL")
                    .containsEntry("executor_submitted_order_type", "MARKET")
                    .containsEntry("executor_submitted_position_side", "BOTH")
                    .containsEntry("executor_submitted_quantity", "0.25")
                    .containsEntry("executor_submitted_reduce_only", "true")
                    .containsEntry("executor_submitted_close_position", "false");
        });
        assertThat(eventBus.values(RiskDecisionEvent.class))
                .singleElement()
                .satisfies(decision -> {
                    assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
                    assertThat(decision.getAttributes())
                            .containsEntry("external_position_remediation_executor_command", "true");
                });
        assertThat(eventBus.values(OrderCommandEvent.class)).isEmpty();
        assertThat(eventBus.values(OrderResultEvent.class))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.getStatus()).isEqualTo(OrderResultStatus.FILLED);
                    assertThat(result.getOriginalQuantity()).isEqualTo("0.25");
                    assertThat(result.getExecutedQuantity()).isEqualTo("0.25");
                });
    }

    @Test
    void submits_hedge_mode_position_close_remediation_as_position_side_market_order_when_policy_allows() {
        restorePositionCloseRemediationDecision("0.25", "CLOSE", Map.of(), "LONG");
        CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
        ReconciliationConfidenceTracker reconciliationTracker =
                new ReconciliationConfidenceTracker(Clock.fixed(NOW, ZoneOffset.UTC));
        reconciliationTracker.record(new ReconciliationObservation(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                TradingEventType.ORDER_RESULT,
                "binance|demo|main|usd_m_futures|BTCUSDT",
                ReconciliationConfidenceStatus.CONFIDENT,
                List.of()
        ));
        OrderExecutionPipeline pipeline = new OrderExecutionPipeline(
                new OrderRiskGate(new ExecutionProperties(null), reconciliationTracker, projection),
                eventBus,
                List.of(new PositionOrderGateway())
        );
        InterventionRemediationCommandPlanner hedgeCommandPlanner =
                new InterventionRemediationCommandPlanner(projection, hedgePositionOrderPolicy());

        InterventionRemediationExecutorService.RemediationExecutionBatch batch =
                service(
                        liveHedgePositionPolicy(InterventionProperties.ExecutableOperation.CLOSE_POSITION),
                        pipeline,
                        hedgeCommandPlanner
                ).execute("binance", "demo", "main", "usd_m_futures");

        assertThat(batch.evaluatedCount()).isEqualTo(1);
        assertThat(batch.blockedCount()).isZero();
        assertThat(batch.submittedCount()).isEqualTo(1);
        assertThat(batch.reports()).singleElement().satisfies(report -> {
            assertThat(report.status())
                    .isEqualTo(InterventionRemediationExecutorService.ExecutionStatus.SUBMITTED_TO_PIPELINE);
            assertThat(report.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CLOSE_POSITION);
            assertThat(report.attributes())
                    .containsEntry("position_execution_mode", "hedge_mode_position_side_close_reduce")
                    .containsEntry("executor_submitted_side", "SELL")
                    .containsEntry("executor_submitted_order_type", "MARKET")
                    .containsEntry("executor_submitted_position_side", "LONG")
                    .containsEntry("executor_submitted_quantity", "0.25")
                    .containsEntry("executor_submitted_reduce_only", "false")
                    .containsEntry("executor_submitted_close_position", "false");
        });
        assertThat(eventBus.values(RiskDecisionEvent.class))
                .singleElement()
                .satisfies(decision -> {
                    assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
                    assertThat(decision.getAttributes())
                            .containsEntry("external_position_remediation_executor_command", "true");
                });
        assertThat(eventBus.values(OrderCommandEvent.class)).isEmpty();
        assertThat(eventBus.values(OrderResultEvent.class))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.getStatus()).isEqualTo(OrderResultStatus.FILLED);
                    assertThat(result.getOriginalQuantity()).isEqualTo("0.25");
                    assertThat(result.getExecutedQuantity()).isEqualTo("0.25");
                });
    }

    @Test
    void submits_position_hedge_remediation_as_opposite_position_side_market_order_when_policy_allows() {
        restorePositionCloseRemediationDecision("0.25", "HEDGE", Map.of());
        CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
        ReconciliationConfidenceTracker reconciliationTracker =
                new ReconciliationConfidenceTracker(Clock.fixed(NOW, ZoneOffset.UTC));
        reconciliationTracker.record(new ReconciliationObservation(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                TradingEventType.ORDER_RESULT,
                "binance|demo|main|usd_m_futures|BTCUSDT",
                ReconciliationConfidenceStatus.CONFIDENT,
                List.of()
        ));
        OrderExecutionPipeline pipeline = new OrderExecutionPipeline(
                new OrderRiskGate(new ExecutionProperties(null), reconciliationTracker, projection),
                eventBus,
                List.of(new PositionOrderGateway())
        );
        InterventionRemediationCommandPlanner hedgeCommandPlanner =
                new InterventionRemediationCommandPlanner(projection, executableHedgePositionOrderPolicy());

        InterventionRemediationExecutorService.RemediationExecutionBatch batch =
                service(
                        liveExecutableHedgePositionPolicy(InterventionProperties.ExecutableOperation.HEDGE_POSITION),
                        pipeline,
                        hedgeCommandPlanner
                ).execute("binance", "demo", "main", "usd_m_futures");

        assertThat(batch.evaluatedCount()).isEqualTo(1);
        assertThat(batch.blockedCount()).isZero();
        assertThat(batch.submittedCount()).isEqualTo(1);
        assertThat(batch.reports()).singleElement().satisfies(report -> {
            assertThat(report.status())
                    .isEqualTo(InterventionRemediationExecutorService.ExecutionStatus.SUBMITTED_TO_PIPELINE);
            assertThat(report.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.HEDGE_POSITION);
            assertThat(report.attributes())
                    .containsEntry("position_execution_mode", "hedge_mode_position_side_hedge")
                    .containsEntry("executor_submitted_side", "SELL")
                    .containsEntry("executor_submitted_order_type", "MARKET")
                    .containsEntry("executor_submitted_position_side", "SHORT")
                    .containsEntry("executor_submitted_quantity", "0.25")
                    .containsEntry("executor_submitted_reduce_only", "false")
                    .containsEntry("executor_submitted_close_position", "false");
        });
        assertThat(eventBus.values(RiskDecisionEvent.class))
                .singleElement()
                .satisfies(decision -> {
                    assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
                    assertThat(decision.getAttributes())
                            .containsEntry("external_position_remediation_executor_command", "true");
                });
        assertThat(eventBus.values(OrderResultEvent.class))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.getStatus()).isEqualTo(OrderResultStatus.FILLED);
                    assertThat(result.getOriginalQuantity()).isEqualTo("0.25");
                    assertThat(result.getExecutedQuantity()).isEqualTo("0.25");
                });
    }

    @Test
    void blocks_position_reduce_execution_when_operation_is_not_allowlisted() {
        restorePositionCloseRemediationDecision("0.25", "REDUCE", Map.of("reduce_fraction", "0.5"));

        InterventionRemediationExecutorService.RemediationExecutionBatch batch =
                service(livePositionPolicy(InterventionProperties.ExecutableOperation.CLOSE_POSITION))
                        .execute("binance", "demo", "main", "usd_m_futures");

        assertThat(batch.evaluatedCount()).isEqualTo(1);
        assertThat(batch.blockedCount()).isEqualTo(1);
        assertThat(batch.reports()).singleElement().satisfies(report -> {
            assertThat(report.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.REDUCE_POSITION);
            assertThat(report.exchangeExecutable()).isTrue();
            assertThat(report.reasons())
                    .containsExactly("remediation:reduce_position", "executor:operation_not_allowed");
        });
    }

    private InterventionRemediationExecutorService service(
            InterventionProperties.RemediationExecutorPolicy policy
    ) {
        return service(policy, null);
    }

    private InterventionRemediationExecutorService service(
            InterventionProperties.RemediationExecutorPolicy policy,
            OrderExecutionPipeline pipeline
    ) {
        return service(policy, pipeline, commandPlanner);
    }

    private InterventionRemediationExecutorService service(
            InterventionProperties.RemediationExecutorPolicy policy,
            OrderExecutionPipeline pipeline,
            InterventionRemediationCommandPlanner commandPlanner
    ) {
        return new InterventionRemediationExecutorService(
                projection,
                commandPlanner,
                new InterventionProperties(null, null, null, null, policy),
                pipeline
        );
    }

    private InterventionProperties.RemediationExecutorPolicy policy(
            boolean enabled,
            boolean exchangeExecutionEnabled,
            boolean reportOnly,
            int maxPlansPerRun
    ) {
        return new InterventionProperties.RemediationExecutorPolicy(
                enabled,
                exchangeExecutionEnabled,
                reportOnly,
                false,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                maxPlansPerRun,
                List.of(),
                InterventionProperties.PositionOrderPolicy.disabled()
        );
    }

    private InterventionProperties.RemediationExecutorPolicy liveCancelPolicy() {
        return livePositionPolicy(InterventionProperties.ExecutableOperation.CANCEL_ORDER);
    }

    private InterventionProperties.RemediationExecutorPolicy livePositionPolicy(
            InterventionProperties.ExecutableOperation operation
    ) {
        return new InterventionProperties.RemediationExecutorPolicy(
                true,
                true,
                false,
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
                List.of(operation),
                enabledPositionOrderPolicy()
        );
    }

    private InterventionProperties.RemediationExecutorPolicy liveHedgePositionPolicy(
            InterventionProperties.ExecutableOperation operation
    ) {
        return new InterventionProperties.RemediationExecutorPolicy(
                true,
                true,
                false,
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
                List.of(operation),
                hedgePositionOrderPolicy()
        );
    }

    private InterventionProperties.RemediationExecutorPolicy liveExecutableHedgePositionPolicy(
            InterventionProperties.ExecutableOperation operation
    ) {
        return new InterventionProperties.RemediationExecutorPolicy(
                true,
                true,
                false,
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
                List.of(operation),
                executableHedgePositionOrderPolicy()
        );
    }

    private static InterventionProperties.PositionOrderPolicy enabledPositionOrderPolicy() {
        return new InterventionProperties.PositionOrderPolicy(
                true,
                "binance",
                "usd_m_futures",
                "BOTH",
                "MARKET",
                true,
                true,
                false,
                false,
                List.of(),
                null,
                false,
                null,
                true,
                null,
                null,
                null,
                true
        );
    }

    private static InterventionProperties.PositionOrderPolicy hedgePositionOrderPolicy() {
        return new InterventionProperties.PositionOrderPolicy(
                true,
                "binance",
                "usd_m_futures",
                "BOTH",
                "MARKET",
                true,
                true,
                true,
                false,
                List.of(),
                null,
                false,
                null,
                true,
                null,
                null,
                null,
                true
        );
    }

    private static InterventionProperties.PositionOrderPolicy executableHedgePositionOrderPolicy() {
        return new InterventionProperties.PositionOrderPolicy(
                true,
                "binance",
                "usd_m_futures",
                "BOTH",
                "MARKET",
                true,
                true,
                true,
                true,
                List.of(),
                null,
                false,
                null,
                true,
                null,
                null,
                null,
                true
        );
    }

    private void restoreCloseRemediationDecisionWithOrder(
            String clientOrderId,
            String remediationId,
            String eventId
    ) {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(order(clientOrderId, eventId.replace("remediation", "order"))),
                List.of(),
                List.of(),
                List.of(orderDecision(clientOrderId, remediationId, eventId)),
                List.of(eventId)
        ));
    }

    private void restoreTwoCloseRemediationDecisionsWithOrders() {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(
                        order("client-1", "evt-order-1"),
                        order("client-2", "evt-order-2")
                ),
                List.of(),
                List.of(),
                List.of(
                        orderDecision("client-1", "remediation-1", "evt-remediation-1"),
                        orderDecision("client-2", "remediation-2", "evt-remediation-2")
                ),
                List.of("evt-remediation-1", "evt-remediation-2")
        ));
    }

    private void restoreFlatPositionCloseDecision() {
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
                        true,
                        "external_position_change",
                        NOW.minusSeconds(1),
                        "evt-position-intervention"
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.RemediationDecisionState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "remediation-position-1",
                        "POSITION",
                        "CLOSE",
                        null,
                        "BOTH",
                        "external_position_change",
                        List.of("intervention:external_position_change"),
                        "automated_remediation_policy",
                        "policy action selected",
                        Map.of(),
                        NOW.minusSeconds(1),
                        "evt-remediation-position-1"
                )),
                List.of("evt-remediation-position-1")
        ));
    }

    private void restorePositionCloseRemediationDecision(
            String positionAmount,
            String action,
            Map<String, String> attributes
    ) {
        restorePositionCloseRemediationDecision(positionAmount, action, attributes, "BOTH");
    }

    private void restorePositionCloseRemediationDecision(
            String positionAmount,
            String action,
            Map<String, String> attributes,
            String positionSide
    ) {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(new TradingStateProjection.PositionState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        positionSide,
                        positionAmount,
                        "50000.00",
                        "50010.00",
                        "12.50",
                        "5",
                        "cross",
                        null,
                        "USER_DATA",
                        true,
                        "external_position_change",
                        NOW.minusSeconds(1),
                        "evt-position-intervention"
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.RemediationDecisionState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "remediation-position-1",
                        "POSITION",
                        action,
                        null,
                        positionSide,
                        "external_position_change",
                        List.of("intervention:external_position_change"),
                        "automated_remediation_policy",
                        "policy action selected",
                        attributes,
                        NOW.minusSeconds(1),
                        "evt-remediation-position-1"
                )),
                List.of("evt-remediation-position-1")
        ));
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
                "exchange-" + clientOrderId,
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

    private TradingStateProjection.RemediationDecisionState orderDecision(
            String clientOrderId,
            String remediationId,
            String eventId
    ) {
        return new TradingStateProjection.RemediationDecisionState(
                "binance",
                "demo",
                "main",
                "usd_m_futures",
                "BTCUSDT",
                remediationId,
                "ORDER",
                "CLOSE",
                clientOrderId,
                null,
                "external_order_observed",
                List.of("intervention:external_order_observed"),
                "automated_remediation_policy",
                "policy action selected",
                Map.of(),
                NOW.minusSeconds(1),
                eventId
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
                    "test-topic",
                    0,
                    envelopes.size() - 1L
            ));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    TradingEventType.ORDER_COMMAND,
                    "test-topic",
                    0,
                    envelopes.size()
            ));
        }

        private <T extends SpecificRecord> List<T> values(Class<T> type) {
            return envelopes.stream()
                    .map(TradingEventEnvelope::value)
                    .filter(type::isInstance)
                    .map(type::cast)
                    .toList();
        }
    }

    private static final class CancelGateway implements OrderExecutionGateway {

        @Override
        public boolean supports(String provider, String environment, String account, String market) {
            return true;
        }

        @Override
        public CompletableFuture<TradingEventEnvelope<OrderResultEvent>> submit(OrderCommandEvent command) {
            OrderResultEvent result = OrderResultEvent.newBuilder()
                    .setEventId("order-result:" + command.getCommandId() + ":canceled")
                    .setSchemaVersion(1)
                    .setCommandId(command.getCommandId())
                    .setProvider(command.getProvider())
                    .setEnvironment(command.getEnvironment())
                    .setAccount(command.getAccount())
                    .setMarket(command.getMarket())
                    .setSymbol(command.getSymbol())
                    .setClientOrderId(command.getTargetClientOrderId())
                    .setExchangeOrderId(command.getTargetExchangeOrderId())
                    .setStatus(OrderResultStatus.CANCELED)
                    .setExchangeStatus("CANCELED")
                    .setPrice(null)
                    .setOriginalQuantity(null)
                    .setExecutedQuantity(null)
                    .setAveragePrice(null)
                    .setCumulativeQuote(null)
                    .setExchangeTransactTimeMicros(NOW)
                    .setObservedAtMicros(NOW)
                    .setRejectCode(null)
                    .setRejectMessage(null)
                    .setAttributes(Map.of("source", "test-cancel-gateway"))
                    .build();
            return CompletableFuture.completedFuture(TradingEventEnvelope.of(
                    TradingEventType.ORDER_RESULT,
                    TradingEventKeys.order(
                            TradingEventType.ORDER_RESULT,
                            command.getProvider().toString(),
                            command.getEnvironment().toString(),
                            command.getAccount().toString(),
                            command.getMarket().toString(),
                            command.getSymbol().toString(),
                            command.getTargetClientOrderId().toString()
                    ),
                    result
            ));
        }
    }

    private static final class PositionOrderGateway implements OrderExecutionGateway {

        @Override
        public boolean supports(String provider, String environment, String account, String market) {
            return true;
        }

        @Override
        public CompletableFuture<TradingEventEnvelope<OrderResultEvent>> submit(OrderCommandEvent command) {
            OrderResultEvent result = OrderResultEvent.newBuilder()
                    .setEventId("order-result:" + command.getCommandId() + ":filled")
                    .setSchemaVersion(1)
                    .setCommandId(command.getCommandId())
                    .setProvider(command.getProvider())
                    .setEnvironment(command.getEnvironment())
                    .setAccount(command.getAccount())
                    .setMarket(command.getMarket())
                    .setSymbol(command.getSymbol())
                    .setClientOrderId(command.getClientOrderId())
                    .setExchangeOrderId("exchange-" + command.getClientOrderId())
                    .setStatus(OrderResultStatus.FILLED)
                    .setExchangeStatus("FILLED")
                    .setPrice(null)
                    .setOriginalQuantity(command.getQuantity())
                    .setExecutedQuantity(command.getQuantity())
                    .setAveragePrice("50000.00")
                    .setCumulativeQuote(null)
                    .setExchangeTransactTimeMicros(NOW)
                    .setObservedAtMicros(NOW)
                    .setRejectCode(null)
                    .setRejectMessage(null)
                    .setAttributes(Map.of(
                            "source", "test-position-gateway",
                            "reduce_only", Boolean.toString(Boolean.TRUE.equals(command.getReduceOnly())),
                            "close_position", Boolean.toString(Boolean.TRUE.equals(command.getClosePosition()))
                    ))
                    .build();
            return CompletableFuture.completedFuture(TradingEventEnvelope.of(
                    TradingEventType.ORDER_RESULT,
                    TradingEventKeys.order(
                            TradingEventType.ORDER_RESULT,
                            command.getProvider().toString(),
                            command.getEnvironment().toString(),
                            command.getAccount().toString(),
                            command.getMarket().toString(),
                            command.getSymbol().toString(),
                            command.getClientOrderId().toString()
                    ),
                    result
            ));
        }
    }
}
