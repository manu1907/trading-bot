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
                .containsEntry("exchange_execution_blocker", "hedge_position_order_policy_disabled");
    }

    @Test
    void keeps_position_hedge_non_executable_when_hedge_order_policy_is_disabled() {
        restorePositionIntervention("0.25", true, "LONG");
        InterventionRemediationCommandPlanner hedgePlanner =
                new InterventionRemediationCommandPlanner(projection, hedgePositionOrderPolicy());

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = hedgePlanner.plan(positionDecision("HEDGE", "LONG"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.HEDGE_POSITION);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.attributes())
                .containsEntry("position_order_command_position_side", "SHORT")
                .containsEntry("exchange_execution_blocker", "hedge_position_order_policy_disabled")
                .containsEntry("exchange_executable", "false");
    }

    @Test
    void plans_position_hedge_as_position_side_market_order_when_hedge_order_policy_is_enabled() {
        restorePositionIntervention("0.25", true, "LONG", "5", "cross", "HEDGE");
        InterventionRemediationCommandPlanner hedgePlanner =
                new InterventionRemediationCommandPlanner(projection, executableHedgePositionOrderPolicy());

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = hedgePlanner.plan(positionDecision("HEDGE", "LONG"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.HEDGE_POSITION);
        assertThat(plan.exchangeExecutable()).isTrue();
        assertThat(plan.attributes())
                .containsEntry("position_order_command_position_side", "SHORT")
                .containsEntry("remediation_order_side", "SELL")
                .containsEntry("position_sizing_policy", "bounded_projection_hedge")
                .containsEntry("reduce_only_required", "false")
                .containsEntry("hedge_mode_required", "true")
                .containsEntry("position_order_reduce_only", "false")
                .containsEntry("position_execution_mode", "hedge_mode_position_side_hedge")
                .containsEntry("exchange_execution_path", "order_execution_pipeline")
                .containsEntry("exchange_executable", "true");
    }

    @Test
    void keeps_hedge_position_order_non_executable_when_position_mode_is_not_observed() {
        restorePositionIntervention("0.25", true, "LONG");
        InterventionRemediationCommandPlanner hedgePlanner =
                new InterventionRemediationCommandPlanner(projection, executableHedgePositionOrderPolicy());

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = hedgePlanner.plan(positionDecision("HEDGE", "LONG"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.HEDGE_POSITION);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.attributes())
                .containsEntry("position_order_required_position_mode", "HEDGE")
                .containsEntry("exchange_execution_blocker", "position_order_position_mode_missing")
                .containsEntry("exchange_executable", "false");
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
        restorePositionIntervention("0.25", true, "LONG", "5", "cross", "HEDGE");
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
        restorePositionIntervention("-0.25", true, "SHORT", "5", "cross", "HEDGE");
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
                        null,
                        true
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
    void keeps_position_close_non_executable_when_symbol_is_not_allowlisted_by_position_policy() {
        restorePositionIntervention("0.25", true);
        InterventionRemediationCommandPlanner restrictedPlanner = new InterventionRemediationCommandPlanner(
                projection,
                constrainedPositionOrderPolicy(List.of("ETHUSDT"), null, null, true)
        );

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan =
                restrictedPlanner.plan(positionDecision("CLOSE"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CLOSE_POSITION);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.attributes())
                .containsEntry("position_order_allowed_symbols", "ETHUSDT")
                .containsEntry("exchange_execution_blocker", "position_order_symbol_policy_missing")
                .containsEntry("exchange_executable", "false");
    }

    @Test
    void keeps_position_close_non_executable_when_quantity_exceeds_position_policy_cap() {
        restorePositionIntervention("0.25", true);
        InterventionRemediationCommandPlanner restrictedPlanner = new InterventionRemediationCommandPlanner(
                projection,
                constrainedPositionOrderPolicy(List.of("BTCUSDT"), "0.10", null, true)
        );

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan =
                restrictedPlanner.plan(positionDecision("CLOSE"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CLOSE_POSITION);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.attributes())
                .containsEntry("position_order_allowed_symbols", "BTCUSDT")
                .containsEntry("position_order_max_quantity", "0.10")
                .containsEntry("exchange_execution_blocker", "position_order_max_quantity_exceeded")
                .containsEntry("exchange_executable", "false");
    }

    @Test
    void plans_position_close_as_executable_chunk_when_close_chunking_policy_is_enabled() {
        restorePositionIntervention("0.25", true);
        InterventionRemediationCommandPlanner chunkingPlanner = new InterventionRemediationCommandPlanner(
                projection,
                constrainedPositionOrderPolicy(List.of("BTCUSDT"), "0.10", true, "10000", true)
        );

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan =
                chunkingPlanner.plan(positionDecision("CLOSE"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CLOSE_POSITION);
        assertThat(plan.exchangeExecutable()).isTrue();
        assertThat(plan.attributes())
                .containsEntry("target_position_quantity", "0.1")
                .containsEntry("position_sizing_policy", "bounded_projection_chunked_close")
                .containsEntry("position_close_chunked", "true")
                .containsEntry("position_close_remaining_quantity_estimate", "0.15")
                .containsEntry("position_order_max_quantity", "0.10")
                .containsEntry("position_order_estimated_notional", "5001")
                .containsEntry("position_execution_mode", "one_way_reduce_only")
                .containsEntry("exchange_execution_path", "order_execution_pipeline")
                .containsEntry("exchange_executable", "true");
    }

    @Test
    void keeps_position_reduce_non_executable_when_quantity_cap_is_exceeded_even_if_close_chunking_is_enabled() {
        restorePositionIntervention("0.25", true);
        InterventionRemediationCommandPlanner chunkingPlanner = new InterventionRemediationCommandPlanner(
                projection,
                constrainedPositionOrderPolicy(List.of("BTCUSDT"), "0.10", true, null, true)
        );

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = chunkingPlanner.plan(positionDecision(
                "REDUCE",
                Map.of("reduce_quantity", "0.125")
        ));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.REDUCE_POSITION);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.attributes())
                .containsEntry("target_position_quantity", "0.125")
                .containsEntry("position_close_chunked", "false")
                .containsEntry("exchange_execution_blocker", "position_order_max_quantity_exceeded")
                .containsEntry("exchange_executable", "false");
    }

    @Test
    void keeps_position_reduce_non_executable_when_estimated_notional_exceeds_position_policy_cap() {
        restorePositionIntervention("0.25", true);
        InterventionRemediationCommandPlanner restrictedPlanner = new InterventionRemediationCommandPlanner(
                projection,
                constrainedPositionOrderPolicy(List.of("BTCUSDT"), "0.25", "100", true)
        );

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan = restrictedPlanner.plan(positionDecision(
                "REDUCE",
                Map.of("reduce_fraction", "0.5")
        ));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.REDUCE_POSITION);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.attributes())
                .containsEntry("target_position_quantity", "0.125")
                .containsEntry("position_order_max_notional", "100")
                .containsEntry("position_order_estimated_notional", "6251.25")
                .containsEntry("exchange_execution_blocker", "position_order_max_notional_exceeded")
                .containsEntry("exchange_executable", "false");
    }

    @Test
    void keeps_position_close_non_executable_when_margin_type_does_not_match_position_policy() {
        restorePositionIntervention("0.25", true, "BOTH", "5", "isolated");
        InterventionRemediationCommandPlanner restrictedPlanner = new InterventionRemediationCommandPlanner(
                projection,
                accountRiskPositionOrderPolicy("cross", null, "5")
        );

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan =
                restrictedPlanner.plan(positionDecision("CLOSE"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CLOSE_POSITION);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.attributes())
                .containsEntry("position_margin_type", "isolated")
                .containsEntry("position_order_required_margin_type", "CROSS")
                .containsEntry("exchange_execution_blocker", "position_order_margin_type_policy_missing")
                .containsEntry("exchange_executable", "false");
    }

    @Test
    void keeps_position_close_non_executable_when_leverage_exceeds_position_policy_cap() {
        restorePositionIntervention("0.25", true, "BOTH", "10", "cross");
        InterventionRemediationCommandPlanner restrictedPlanner = new InterventionRemediationCommandPlanner(
                projection,
                accountRiskPositionOrderPolicy("cross", "1", "5")
        );

        InterventionRemediationCommandPlanner.RemediationCommandPlan plan =
                restrictedPlanner.plan(positionDecision("CLOSE"));

        assertThat(plan.status()).isEqualTo(InterventionRemediationCommandPlanner.PlanStatus.READY);
        assertThat(plan.operation()).isEqualTo(InterventionRemediationCommandPlanner.Operation.CLOSE_POSITION);
        assertThat(plan.exchangeExecutable()).isFalse();
        assertThat(plan.attributes())
                .containsEntry("position_leverage", "10")
                .containsEntry("position_order_max_leverage", "5")
                .containsEntry("exchange_execution_blocker", "position_order_max_leverage_violated")
                .containsEntry("exchange_executable", "false");
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
        restorePositionIntervention(positionAmount, externalIntervention, positionSide, "5", "cross");
    }

    private void restorePositionIntervention(
            String positionAmount,
            boolean externalIntervention,
            String positionSide,
            String leverage,
            String marginType
    ) {
        restorePositionIntervention(positionAmount, externalIntervention, positionSide, leverage, marginType, null);
    }

    private void restorePositionIntervention(
            String positionAmount,
            boolean externalIntervention,
            String positionSide,
            String leverage,
            String marginType,
            String positionMode
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
                        positionMode,
                        positionAmount,
                        "50000.00",
                        "50010.00",
                        "12.50",
                        leverage,
                        marginType,
                        null,
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
                null,
                true
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
                null,
                true
        );
    }

    private InterventionProperties.PositionOrderPolicy executableHedgePositionOrderPolicy() {
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
                null,
                true
        );
    }

    private InterventionProperties.PositionOrderPolicy constrainedPositionOrderPolicy(
            List<String> allowedSymbols,
            String maxQuantity,
            String maxNotional,
            boolean rejectUnboundedNotional
    ) {
        return constrainedPositionOrderPolicy(
                allowedSymbols,
                maxQuantity,
                false,
                maxNotional,
                rejectUnboundedNotional
        );
    }

    private InterventionProperties.PositionOrderPolicy accountRiskPositionOrderPolicy(
            String requiredMarginType,
            String minLeverage,
            String maxLeverage
    ) {
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
                List.of("BTCUSDT"),
                null,
                false,
                null,
                true,
                requiredMarginType,
                null,
                minLeverage,
                maxLeverage,
                true
        );
    }

    private InterventionProperties.PositionOrderPolicy constrainedPositionOrderPolicy(
            List<String> allowedSymbols,
            String maxQuantity,
            boolean chunkClose,
            String maxNotional,
            boolean rejectUnboundedNotional
    ) {
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
                allowedSymbols,
                maxQuantity,
                chunkClose,
                maxNotional,
                rejectUnboundedNotional,
                null,
                null,
                null,
                null,
                true
        );
    }
}
