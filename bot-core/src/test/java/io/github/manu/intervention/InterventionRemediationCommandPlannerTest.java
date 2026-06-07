package io.github.manu.intervention;

import io.github.manu.events.v1.RemediationDecisionEvent;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InterventionRemediationCommandPlannerTest {

    private static final Instant NOW = Instant.parse("2026-06-06T15:15:00Z");

    private final TradingStateProjection projection = new TradingStateProjection();
    private final InterventionRemediationCommandPlanner planner = new InterventionRemediationCommandPlanner(projection);

    @Test
    void plans_order_close_as_executable_cancel_intent_with_target_identity() {
        restoreOrderIntervention("external_order_observed", true);

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = planner.plan(orderDecision("CLOSE"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CANCEL_ORDER);
        assertThat(plan.exchangeExecutable()).isTrue();
        assertThat(plan.reasons()).containsExactly("remediation:cancel_external_order");
        assertThat(plan.attributes())
                .containsEntry("target_client_order_id", "client-1")
                .containsEntry("target_exchange_order_id", "12345")
                .containsEntry("target_order_status", "ACCEPTED")
                .containsEntry("exchange_execution_path", "order_execution_pipeline")
                .containsEntry("exchange_executable", "true");
    }

    @Test
    void refuses_order_plan_when_projection_intervention_reason_is_stale() {
        restoreOrderIntervention("unplanned_managed_order_change", true);

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = planner.plan(orderDecision("ADOPT"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.STALE_PROJECTION);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.UNSUPPORTED);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.reasons()).containsExactly("projection:order_intervention_reason_mismatch");
    }

    @Test
    void plans_open_position_hedge_or_replan_as_non_executable_hedge_intent() {
        restorePositionIntervention("0.25", true);

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = planner.plan(positionDecision("HEDGE_OR_REPLAN"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.HEDGE_POSITION);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.reasons()).containsExactly("remediation:hedge_or_replan");
        assertThat(plan.attributes())
                .containsEntry("position_amount", "0.25")
                .containsEntry("position_abs_amount", "0.25")
                .containsEntry("alternative_operation", "REPLAN_FROM_PROJECTION")
                .containsEntry("exchange_execution_blocker", "bounded_position_sizing_policy_missing");
    }

    @Test
    void marks_flat_position_close_as_no_action() {
        restorePositionIntervention("0", true);

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = planner.plan(positionDecision("CLOSE"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.NO_ACTION);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CLOSE_POSITION);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.reasons()).containsExactly("projection:position_already_flat");
    }

    @Test
    void rejects_position_close_when_amount_is_invalid() {
        restorePositionIntervention("invalid", true);

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = planner.plan(positionDecision("CLOSE"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.INSUFFICIENT_DATA);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CLOSE_POSITION);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.reasons()).containsExactly("projection:position_amount_invalid");
    }

    @Test
    void plans_pause_account_without_exchange_execution() {
        restorePositionIntervention("0.25", true);

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = planner.plan(positionDecision("PAUSE_ACCOUNT"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.PAUSE_ACCOUNT);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.attributes()).containsEntry("exchange_executable", "false");
    }

    @Test
    void marks_operator_review_as_not_supported_for_command_planning() {
        restoreOrderIntervention("external_order_observed", true);

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = planner.plan(orderDecision("OPERATOR_REVIEW"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.NOT_SUPPORTED);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.OPERATOR_REVIEW);
        assertThat(plan.reasons()).containsExactly("remediation:operator_review_required");
    }

    private RemediationDecisionEvent orderDecision(String action) {
        return RemediationDecisionEvent.newBuilder()
                .setEventId("evt-remediation-001")
                .setSchemaVersion(1)
                .setRemediationId("remediation-001")
                .setProvider("binance")
                .setEnvironment("demo")
                .setAccount("main")
                .setMarket("usd_m_futures")
                .setSymbol("BTCUSDT")
                .setScope("ORDER")
                .setAction(action)
                .setClientOrderId("client-1")
                .setPositionSide(null)
                .setInterventionReason("external_order_observed")
                .setReasons(List.of("intervention:external_order_observed"))
                .setDecidedBy("automated_remediation_policy")
                .setDecisionReason("policy action selected")
                .setDecidedAtMicros(NOW)
                .setAttributes(Map.of())
                .build();
    }

    private RemediationDecisionEvent positionDecision(String action) {
        return RemediationDecisionEvent.newBuilder(orderDecision(action))
                .setScope("POSITION")
                .setClientOrderId(null)
                .setPositionSide("BOTH")
                .setInterventionReason("external_position_change")
                .setReasons(List.of("intervention:external_position_change"))
                .build();
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

    private void restorePositionIntervention(String positionAmount, boolean externalIntervention) {
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(new TradingStateProjection.PositionState(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        "BOTH",
                        positionAmount,
                        "50000.00",
                        "50010.00",
                        "12.50",
                        "USER_DATA",
                        externalIntervention,
                        "external_position_change",
                        NOW.minusSeconds(1),
                        "evt-position-intervention"
                )),
                List.of(),
                List.of(),
                List.of()
        ));
    }
}
