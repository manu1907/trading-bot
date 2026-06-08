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
    private final InterventionRemediationCommandPlanner planner =
            new InterventionRemediationCommandPlanner(projection, enabledPositionOrderPolicy());

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
                .containsEntry("target_position_quantity", "0.25")
                .containsEntry("position_direction", "LONG")
                .containsEntry("remediation_order_side", "SELL")
                .containsEntry("position_sizing_policy", "bounded_projection_hedge")
                .containsEntry("reduce_only_required", "false")
                .containsEntry("hedge_mode_required", "true")
                .containsEntry("alternative_operation", "REPLAN_FROM_PROJECTION")
                .containsEntry("exchange_execution_blocker", "hedge_mode_provider_policy_missing");
    }

    @Test
    void plans_position_close_with_full_projected_size_and_reduce_only_requirement() {
        restorePositionIntervention("-0.25", true);

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = planner.plan(positionDecision("CLOSE"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CLOSE_POSITION);
        assertThat(plan.exchangeExecutable()).isTrue();
        assertThat(plan.reasons()).containsExactly("remediation:close_position");
        assertThat(plan.attributes())
                .containsEntry("position_abs_amount", "0.25")
                .containsEntry("target_position_quantity", "0.25")
                .containsEntry("position_direction", "SHORT")
                .containsEntry("remediation_order_side", "BUY")
                .containsEntry("position_sizing_policy", "bounded_projection_full_close")
                .containsEntry("reduce_only_required", "true")
                .containsEntry("hedge_mode_required", "false")
                .containsEntry("position_order_type", "MARKET")
                .containsEntry("position_order_reduce_only", "true")
                .containsEntry("position_order_close_position", "false")
                .containsEntry("position_execution_mode", "one_way_reduce_only")
                .containsEntry("exchange_execution_path", "order_execution_pipeline")
                .containsEntry("exchange_executable", "true");
    }

    @Test
    void plans_position_reduce_with_explicit_fraction_bounded_by_projected_size() {
        restorePositionIntervention("0.25", true);

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = planner.plan(positionDecision(
                "REDUCE",
                Map.of("reduce_fraction", "0.5")
        ));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.REDUCE_POSITION);
        assertThat(plan.exchangeExecutable()).isTrue();
        assertThat(plan.attributes())
                .containsEntry("position_abs_amount", "0.25")
                .containsEntry("target_position_quantity", "0.125")
                .containsEntry("position_sizing_policy", "bounded_projection_reduce")
                .containsEntry("reduce_only_required", "true")
                .containsEntry("position_order_type", "MARKET")
                .containsEntry("position_execution_mode", "one_way_reduce_only")
                .containsEntry("exchange_execution_path", "order_execution_pipeline")
                .containsEntry("exchange_executable", "true");
    }

    @Test
    void keeps_position_close_non_executable_for_hedge_mode_position_side_until_hedge_policy_exists() {
        restorePositionIntervention("0.25", true, "LONG");

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = planner.plan(positionDecision("CLOSE", "LONG"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CLOSE_POSITION);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.attributes())
                .containsEntry("position_side", "LONG")
                .containsEntry("reduce_only_required", "true")
                .containsEntry("exchange_execution_blocker", "hedge_mode_reduce_only_position_side_unsupported")
                .containsEntry("exchange_executable", "false");
    }

    @Test
    void plans_hedge_mode_position_close_as_position_side_market_order_when_policy_is_enabled() {
        restorePositionIntervention("0.25", true, "LONG");
        InterventionRemediationCommandPlanner hedgePlanner =
                new InterventionRemediationCommandPlanner(projection, hedgePositionOrderPolicy());

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan =
                hedgePlanner.plan(positionDecision("CLOSE", "LONG"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CLOSE_POSITION);
        assertThat(plan.exchangeExecutable()).isTrue();
        assertThat(plan.attributes())
                .containsEntry("position_side", "LONG")
                .containsEntry("position_abs_amount", "0.25")
                .containsEntry("target_position_quantity", "0.25")
                .containsEntry("remediation_order_side", "SELL")
                .containsEntry("position_sizing_policy", "bounded_projection_full_close")
                .containsEntry("reduce_only_required", "true")
                .containsEntry("position_order_type", "MARKET")
                .containsEntry("position_order_reduce_only", "false")
                .containsEntry("position_order_close_position", "false")
                .containsEntry("position_execution_mode", "hedge_mode_position_side_close_reduce")
                .containsEntry("exchange_execution_path", "order_execution_pipeline")
                .containsEntry("exchange_executable", "true");
    }

    @Test
    void plans_hedge_mode_position_reduce_with_explicit_fraction_bounded_by_projected_size() {
        restorePositionIntervention("-0.25", true, "SHORT");
        InterventionRemediationCommandPlanner hedgePlanner =
                new InterventionRemediationCommandPlanner(projection, hedgePositionOrderPolicy());

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = hedgePlanner.plan(positionDecision(
                "REDUCE",
                "SHORT",
                Map.of("reduce_fraction", "0.5")
        ));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.REDUCE_POSITION);
        assertThat(plan.exchangeExecutable()).isTrue();
        assertThat(plan.attributes())
                .containsEntry("position_side", "SHORT")
                .containsEntry("position_abs_amount", "0.25")
                .containsEntry("target_position_quantity", "0.125")
                .containsEntry("position_direction", "SHORT")
                .containsEntry("remediation_order_side", "BUY")
                .containsEntry("position_sizing_policy", "bounded_projection_reduce")
                .containsEntry("position_order_reduce_only", "false")
                .containsEntry("position_execution_mode", "hedge_mode_position_side_close_reduce")
                .containsEntry("exchange_execution_path", "order_execution_pipeline")
                .containsEntry("exchange_executable", "true");
    }

    @Test
    void keeps_position_close_non_executable_when_position_order_policy_is_disabled() {
        restorePositionIntervention("0.25", true);
        InterventionRemediationCommandPlanner disabledPlanner = new InterventionRemediationCommandPlanner(projection);

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = disabledPlanner.plan(positionDecision("CLOSE"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CLOSE_POSITION);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.attributes())
                .containsEntry("exchange_execution_blocker", "position_order_execution_policy_disabled")
                .containsEntry("exchange_executable", "false");
    }

    @Test
    void keeps_position_reduce_non_executable_when_market_does_not_match_policy() {
        restorePositionIntervention("0.25", true);
        InterventionRemediationCommandPlanner ethPlanner = new InterventionRemediationCommandPlanner(
                projection,
                new InterventionProperties.PositionOrderPolicy(
                        true,
                        "binance",
                        "coin_m_futures",
                        "BOTH",
                        "MARKET",
                        true,
                        true,
                        false
                )
        );

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = ethPlanner.plan(positionDecision(
                "REDUCE",
                Map.of("reduce_fraction", "0.5")
        ));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.REDUCE_POSITION);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.attributes()).containsEntry("exchange_execution_blocker", "position_order_market_policy_missing");
    }

    @Test
    void rejects_position_reduce_without_explicit_bounded_size() {
        restorePositionIntervention("0.25", true);

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = planner.plan(positionDecision("REDUCE"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.INSUFFICIENT_DATA);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.REDUCE_POSITION);
        assertThat(plan.reasons()).containsExactly("remediation:reduce_size_missing");
    }

    @Test
    void rejects_position_reduce_when_quantity_exceeds_projected_size() {
        restorePositionIntervention("0.25", true);

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = planner.plan(positionDecision(
                "REDUCE",
                Map.of("reduce_quantity", "0.50")
        ));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.INSUFFICIENT_DATA);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.REDUCE_POSITION);
        assertThat(plan.reasons()).containsExactly("remediation:reduce_size_exceeds_position");
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
    void marks_pause_release_as_completed_governance_no_action() {
        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = planner.plan(pauseReleaseDecision());

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.NO_ACTION);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.RELEASE_PAUSE);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.reasons()).containsExactly("remediation:pause_release_recorded");
        assertThat(plan.attributes())
                .containsEntry("pause_scope", "SYMBOL")
                .containsEntry("pause_target", "BTCUSDT")
                .containsEntry("source_pause_remediation_id", "remediation-pause-001")
                .containsEntry("exchange_executable", "false");
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
        return positionDecision(action, "BOTH");
    }

    private RemediationDecisionEvent positionDecision(String action, String positionSide) {
        return positionDecision(action, positionSide, Map.of());
    }

    private RemediationDecisionEvent positionDecision(String action, Map<CharSequence, CharSequence> attributes) {
        return positionDecision(action, "BOTH", attributes);
    }

    private RemediationDecisionEvent positionDecision(
            String action,
            String positionSide,
            Map<CharSequence, CharSequence> attributes
    ) {
        return RemediationDecisionEvent.newBuilder(orderDecision(action))
                .setScope("POSITION")
                .setClientOrderId(null)
                .setPositionSide(positionSide)
                .setInterventionReason("external_position_change")
                .setReasons(List.of("intervention:external_position_change"))
                .setAttributes(attributes)
                .build();
    }

    private RemediationDecisionEvent pauseReleaseDecision() {
        return RemediationDecisionEvent.newBuilder(orderDecision("RELEASE_SYMBOL_PAUSE"))
                .setRemediationId("pause-release-001")
                .setScope("PAUSE_GOVERNANCE")
                .setClientOrderId(null)
                .setPositionSide(null)
                .setInterventionReason("external_position_change")
                .setReasons(List.of("pause_governance:release"))
                .setAttributes(Map.of(
                        "pause_scope",
                        "SYMBOL",
                        "pause_target",
                        "BTCUSDT",
                        "source_pause_remediation_id",
                        "remediation-pause-001"
                ))
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
        restorePositionIntervention(positionAmount, externalIntervention, "BOTH");
    }

    private void restorePositionIntervention(String positionAmount, boolean externalIntervention, String positionSide) {
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

    private InterventionProperties.PositionOrderPolicy enabledPositionOrderPolicy() {
        return new InterventionProperties.PositionOrderPolicy(
                true,
                "binance",
                "usd_m_futures",
                "BOTH",
                "MARKET",
                true,
                true,
                false
        );
    }

    private InterventionProperties.PositionOrderPolicy hedgePositionOrderPolicy() {
        return new InterventionProperties.PositionOrderPolicy(
                true,
                "binance",
                "usd_m_futures",
                "BOTH",
                "MARKET",
                true,
                true,
                true
        );
    }
}
