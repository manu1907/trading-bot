package io.github.manu.intervention;

import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InterventionRemediationExecutorServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-07T09:30:00Z");

    private final TradingStateProjection projection = new TradingStateProjection();
    private final InterventionRemediationCommandPlanner commandPlanner =
            new InterventionRemediationCommandPlanner(projection);

    @Test
    void returns_disabled_batch_when_executor_policy_is_disabled() {
        restoreCloseRemediationDecisionWithOrder("client-1", "remediation-1", "evt-remediation-1");

        InterventionRemediationExecutorService.RemediationExecutionBatch batch =
                service(policy(false, false, true, 25)).dryRun("binance", "demo", "main", "usd_m_futures");

        assertThat(batch.enabled()).isFalse();
        assertThat(batch.evaluatedCount()).isZero();
        assertThat(batch.reports()).isEmpty();
    }

    @Test
    void blocks_non_executable_cancel_plan_in_report_only_mode() {
        restoreCloseRemediationDecisionWithOrder("client-1", "remediation-1", "evt-remediation-1");

        InterventionRemediationExecutorService.RemediationExecutionBatch batch =
                service(policy(true, false, true, 25)).dryRun("binance", "demo", "main", "usd_m_futures");

        assertThat(batch.enabled()).isTrue();
        assertThat(batch.exchangeExecutionEnabled()).isFalse();
        assertThat(batch.dryRunOnly()).isTrue();
        assertThat(batch.evaluatedCount()).isEqualTo(1);
        assertThat(batch.blockedCount()).isEqualTo(1);
        assertThat(batch.dryRunCount()).isZero();
        assertThat(batch.noActionCount()).isZero();
        assertThat(batch.reports()).singleElement().satisfies(report -> {
            assertThat(report.remediationId()).isEqualTo("remediation-1");
            assertThat(report.planStatus()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
            assertThat(report.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CANCEL_ORDER);
            assertThat(report.status()).isEqualTo(InterventionRemediationExecutorService.ExecutionStatus.BLOCKED);
            assertThat(report.exchangeExecutable()).isFalse();
            assertThat(report.reasons())
                    .containsExactly("remediation:cancel_external_order", "executor:plan_not_exchange_executable");
            assertThat(report.attributes())
                    .containsEntry("executor_status", "BLOCKED")
                    .containsEntry("executor_reason", "executor:plan_not_exchange_executable")
                    .containsEntry("executor_exchange_execution_enabled", "false");
        });
    }

    @Test
    void reports_no_action_for_flat_position_close() {
        restoreFlatPositionCloseDecision();

        InterventionRemediationExecutorService.RemediationExecutionBatch batch =
                service(policy(true, false, true, 25)).dryRun("binance", "demo", "main", "usd_m_futures");

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
                service(policy(true, false, true, 1)).dryRun("binance", "demo", "main", "usd_m_futures");

        assertThat(batch.evaluatedCount()).isEqualTo(1);
        assertThat(batch.reports()).hasSize(1);
    }

    private InterventionRemediationExecutorService service(
            InterventionProperties.RemediationExecutorPolicy policy
    ) {
        return new InterventionRemediationExecutorService(
                projection,
                commandPlanner,
                new InterventionProperties(null, null, null, null, policy)
        );
    }

    private InterventionProperties.RemediationExecutorPolicy policy(
            boolean enabled,
            boolean exchangeExecutionEnabled,
            boolean dryRunOnly,
            int maxPlansPerRun
    ) {
        return new InterventionProperties.RemediationExecutorPolicy(
                enabled,
                exchangeExecutionEnabled,
                dryRunOnly,
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
                List.of()
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
}
