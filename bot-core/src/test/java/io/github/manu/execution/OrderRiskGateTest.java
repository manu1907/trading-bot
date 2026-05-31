package io.github.manu.execution;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.OrderResultStatus;
import io.github.manu.events.v1.RiskDecision;
import io.github.manu.events.v1.RiskDecisionEvent;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import io.github.manu.reconciliation.ReconciliationConfidenceStatus;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import io.github.manu.reconciliation.ReconciliationObservation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRiskGateTest {

    private static final String PROVIDER = "binance";
    private static final String ENVIRONMENT = "demo";
    private static final String ACCOUNT = "main";
    private static final String MARKET = "usd_m_futures";
    private static final String SYMBOL = "BTCUSDT";
    private static final Instant NOW = Instant.parse("2026-05-26T12:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ReconciliationConfidenceTracker reconciliationTracker = new ReconciliationConfidenceTracker(clock);

    @Test
    void rejects_order_when_target_has_no_reconciliation_observations() {
        RiskDecisionEvent decision = gate(defaultProperties()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("reconciliation:no_observations");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes()).containsEntry("reconciliation_status", "NO_OBSERVATIONS");
        assertThat(decision.getAttributes()).containsEntry("reconciliation_observed_states", "0");
        assertThat(decision.getAttributes()).containsEntry("external_order_interventions", "0");
        assertThat(decision.getAttributes()).containsEntry("external_position_interventions", "0");
        assertThat(decision.getAttributes()).containsEntry("unknown_order_statuses", "0");
        assertThat(decision.getDecidedAtMicros()).isEqualTo(NOW);
    }

    @Test
    void approves_order_when_reconciliation_is_confident() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        TradingEventEnvelope<RiskDecisionEvent> envelope = gate(defaultProperties()).evaluate(commandEnvelope());

        assertThat(envelope.eventType()).isEqualTo(TradingEventType.RISK_DECISION);
        assertThat(envelope.key().getEntityId()).isEqualTo(SYMBOL);
        assertThat(envelope.key().getPartitionKey()).contains("risk_decision|symbol|binance|demo|main|usd_m_futures|btcusdt");
        assertThat(envelope.value().getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(envelope.value().getReasons()).containsExactly("risk_gate:approved");
        assertThat(envelope.value().getMaxQuantity()).isEqualTo("0.001");
        assertThat(envelope.value().getAttributes()).containsEntry("reconciliation_status", "CONFIDENT");
        assertThat(envelope.value().getAttributes()).containsEntry("reconciliation_observed_states", "1");
    }

    @Test
    void rejects_order_when_reconciliation_is_degraded() {
        recordReconciliation(ReconciliationConfidenceStatus.MISSING_PROJECTION);

        RiskDecisionEvent decision = gate(defaultProperties()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("reconciliation:degraded");
        assertThat(decision.getAttributes()).containsEntry("reconciliation_status", "DEGRADED");
        assertThat(decision.getAttributes()).containsEntry("reconciliation_degraded_states", "1");
    }

    @Test
    void requires_manual_review_when_target_has_external_order_intervention() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithExternalIntervention()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("intervention:external_order");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes()).containsEntry("external_order_interventions", "1");
        assertThat(decision.getAttributes()).containsEntry("external_order_intervention_action", "MANUAL_REVIEW");
    }

    @Test
    void requires_manual_review_when_target_has_external_position_intervention() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithExternalPositionIntervention()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("intervention:external_position");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes()).containsEntry("external_position_interventions", "1");
        assertThat(decision.getAttributes()).containsEntry("external_position_intervention_action", "MANUAL_REVIEW");
    }

    @Test
    void requires_manual_review_when_target_has_unknown_order_status() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithUnknownOrderStatus()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_status:unknown");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes()).containsEntry("unknown_order_statuses", "1");
        assertThat(decision.getAttributes()).containsEntry("unknown_order_status_action", "MANUAL_REVIEW");
    }

    @Test
    void requires_manual_review_when_target_has_unresolved_order_command() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithUnresolvedOrderCommand()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_command:unresolved");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes()).containsEntry("unresolved_order_commands", "1");
        assertThat(decision.getAttributes()).containsEntry("pending_order_command_action", "MANUAL_REVIEW");
    }

    @Test
    void rejects_order_when_projected_command_id_already_exists_after_restart() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithProjectedOrder("cmd-001", "tb-lfa-old"))
                .evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("execution:projected_duplicate_command_id");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes())
                .containsEntry("projected_duplicate_command_id", "cmd-001")
                .containsEntry("projected_duplicate_client_order_id", "tb-lfa-old")
                .containsEntry("projected_idempotency_reject_duplicates", "true");
    }

    @Test
    void rejects_order_when_projected_client_order_id_already_exists_after_restart() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithProjectedOrder("cmd-old", "tb-lfa-001"))
                .evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("execution:projected_duplicate_client_order_id");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes())
                .containsEntry("projected_duplicate_command_id", "cmd-old")
                .containsEntry("projected_duplicate_client_order_id", "tb-lfa-001");
    }

    @Test
    void can_be_configured_to_allow_projected_duplicate_order_identity() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        ExecutionProperties properties = new ExecutionProperties(
                null,
                new ExecutionProperties.RiskGate(
                        true,
                        new ExecutionProperties.Reconciliation(false, true, true)
                ),
                new ExecutionProperties.Idempotency(true, 100_000, false)
        );

        RiskDecisionEvent decision = gate(properties, projectionWithProjectedOrder("cmd-001", "tb-lfa-001"))
                .evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getAttributes()).containsEntry("projected_idempotency_reject_duplicates", "false");
    }

    @Test
    void can_be_configured_to_reject_external_order_intervention_without_manual_review() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        ExecutionProperties properties = new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true),
                new ExecutionProperties.ManualIntervention(
                        true,
                        true,
                        ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS,
                        ExecutionProperties.InterventionAction.MANUAL_REVIEW
                )
        ));

        RiskDecisionEvent decision = gate(properties, projectionWithExternalIntervention()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("intervention:external_order");
        assertThat(decision.getAttributes()).containsEntry("external_order_intervention_action", "REJECT_NEW_COMMANDS");
    }

    @Test
    void can_be_configured_to_reject_unknown_order_status_without_manual_review() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        ExecutionProperties properties = new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true),
                null,
                new ExecutionProperties.UnknownOrderStatus(
                        true,
                        ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS
                )
        ));

        RiskDecisionEvent decision = gate(properties, projectionWithUnknownOrderStatus()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("order_status:unknown");
        assertThat(decision.getAttributes()).containsEntry("unknown_order_status_action", "REJECT_NEW_COMMANDS");
    }

    @Test
    void can_be_configured_to_reject_unresolved_order_command_without_manual_review() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        ExecutionProperties properties = new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true),
                null,
                null,
                new ExecutionProperties.PendingOrderCommand(
                        true,
                        ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS
                )
        ));

        RiskDecisionEvent decision = gate(properties, projectionWithUnresolvedOrderCommand()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("order_command:unresolved");
        assertThat(decision.getAttributes()).containsEntry("pending_order_command_action", "REJECT_NEW_COMMANDS");
    }

    @Test
    void can_be_configured_to_allow_external_position_intervention() {
        ExecutionProperties properties = new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true),
                new ExecutionProperties.ManualIntervention(true, false)
        ));

        RiskDecisionEvent decision = gate(properties, projectionWithExternalPositionIntervention()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getAttributes()).containsEntry("external_position_interventions", "1");
    }

    @Test
    void can_be_configured_to_allow_external_order_intervention() {
        ExecutionProperties properties = new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true),
                new ExecutionProperties.ManualIntervention(false)
        ));

        RiskDecisionEvent decision = gate(properties, projectionWithExternalIntervention()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getAttributes()).containsEntry("external_order_interventions", "1");
    }

    @Test
    void can_be_configured_to_allow_unknown_order_status() {
        ExecutionProperties properties = new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true),
                null,
                new ExecutionProperties.UnknownOrderStatus(false, null)
        ));

        RiskDecisionEvent decision = gate(properties, projectionWithUnknownOrderStatus()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getAttributes()).containsEntry("unknown_order_statuses", "1");
        assertThat(decision.getAttributes()).containsEntry("unknown_order_status_action", "ALLOW_NEW_COMMANDS");
    }

    @Test
    void can_be_configured_to_allow_unresolved_order_command() {
        ExecutionProperties properties = new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true),
                null,
                null,
                new ExecutionProperties.PendingOrderCommand(false, null)
        ));

        RiskDecisionEvent decision = gate(properties, projectionWithUnresolvedOrderCommand()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getAttributes()).containsEntry("unresolved_order_commands", "1");
        assertThat(decision.getAttributes()).containsEntry("pending_order_command_action", "ALLOW_NEW_COMMANDS");
    }

    @Test
    void can_be_configured_to_allow_when_gate_is_disabled() {
        ExecutionProperties properties = new ExecutionProperties(new ExecutionProperties.RiskGate(
                false,
                new ExecutionProperties.Reconciliation(true, true, true)
        ));

        RiskDecisionEvent decision = gate(properties).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:disabled");
        assertThat(decision.getAttributes()).containsEntry("reconciliation_status", "NO_OBSERVATIONS");
    }

    @Test
    void can_be_configured_to_allow_without_reconciliation_requirement() {
        ExecutionProperties properties = new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true)
        ));

        RiskDecisionEvent decision = gate(properties).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
    }

    @Test
    void rejects_non_positive_quantity_before_provider_mapping() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties()).evaluate(commandWithQuantity("0"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("order_limit:non_positive_quantity");
        assertThat(decision.getAttributes()).containsEntry("order_limit_enabled", "true");
    }

    @Test
    void rejects_invalid_numeric_order_field_before_provider_mapping() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties()).evaluate(commandWithQuantity("not-a-number"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("order_limit:invalid_numeric");
    }

    @Test
    void rejects_order_above_configured_max_quantity_before_provider_mapping() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        ExecutionProperties properties = propertiesWithOrderLimit(new ExecutionProperties.OrderLimit(
                true,
                true,
                "0.0005",
                null,
                true,
                ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS
        ));

        RiskDecisionEvent decision = gate(properties).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("order_limit:max_quantity");
        assertThat(decision.getAttributes()).containsEntry("order_limit_max_quantity", "0.0005");
    }

    @Test
    void requires_manual_review_when_order_exceeds_configured_max_notional() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        ExecutionProperties properties = propertiesWithOrderLimit(new ExecutionProperties.OrderLimit(
                true,
                true,
                null,
                "40",
                true,
                ExecutionProperties.InterventionAction.MANUAL_REVIEW
        ));

        RiskDecisionEvent decision = gate(properties).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_limit:max_notional");
        assertThat(decision.getAttributes())
                .containsEntry("order_limit_max_notional", "40")
                .containsEntry("order_limit_computed_notional", "50");
    }

    @Test
    void rejects_unbounded_notional_when_max_notional_requires_computable_exposure() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        ExecutionProperties properties = propertiesWithOrderLimit(new ExecutionProperties.OrderLimit(
                true,
                true,
                null,
                "40",
                true,
                ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS
        ));

        RiskDecisionEvent decision = gate(properties).evaluate(commandWithoutPriceOrQuoteQuantity());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("order_limit:unbounded_notional");
    }

    @Test
    void applies_most_specific_target_order_limit_before_provider_mapping() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        ExecutionProperties properties = propertiesWithOrderLimit(new ExecutionProperties.OrderLimit(
                true,
                true,
                null,
                "100",
                true,
                ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS,
                List.of(
                        new ExecutionProperties.OrderLimit.TargetLimit(
                                PROVIDER,
                                ENVIRONMENT,
                                ACCOUNT,
                                MARKET,
                                null,
                                null,
                                "75",
                                true,
                                ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS
                        ),
                        new ExecutionProperties.OrderLimit.TargetLimit(
                                PROVIDER,
                                ENVIRONMENT,
                                ACCOUNT,
                                MARKET,
                                SYMBOL,
                                null,
                                "40",
                                true,
                                ExecutionProperties.InterventionAction.MANUAL_REVIEW
                        )
                )
        ));

        RiskDecisionEvent decision = gate(properties).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_limit:max_notional");
        assertThat(decision.getAttributes())
                .containsEntry("order_limit_max_notional", "40")
                .containsEntry("order_limit_scope", "binance|demo|main|usd_m_futures|BTCUSDT")
                .containsEntry("order_limit_action", "MANUAL_REVIEW");
    }

    private OrderRiskGate gate(ExecutionProperties properties) {
        return new OrderRiskGate(properties, reconciliationTracker, clock);
    }

    private OrderRiskGate gate(ExecutionProperties properties, TradingStateProjection projection) {
        return new OrderRiskGate(properties, reconciliationTracker, projection, clock);
    }

    private ExecutionProperties defaultProperties() {
        return new ExecutionProperties(null);
    }

    private ExecutionProperties propertiesWithOrderLimit(ExecutionProperties.OrderLimit orderLimit) {
        return new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true),
                null,
                null,
                null,
                orderLimit
        ));
    }

    private void recordReconciliation(ReconciliationConfidenceStatus status) {
        reconciliationTracker.record(new ReconciliationObservation(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                TradingEventType.BALANCE_UPDATE,
                PROVIDER + "|" + ENVIRONMENT + "|" + ACCOUNT + "|" + MARKET + "|USDT",
                status,
                List.of()
        ));
    }

    private TradingEventEnvelope<OrderCommandEvent> commandEnvelope() {
        OrderCommandEvent command = command();
        return TradingEventEnvelope.of(
                TradingEventType.ORDER_COMMAND,
                TradingEventKeys.order(
                        TradingEventType.ORDER_COMMAND,
                        PROVIDER,
                        ENVIRONMENT,
                        ACCOUNT,
                        MARKET,
                        SYMBOL,
                        command.getClientOrderId().toString()
                ),
                command
        );
    }

    private TradingStateProjection projectionWithExternalIntervention() {
        TradingStateProjection projection = new TradingStateProjection();
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.OrderState(
                        PROVIDER,
                        ENVIRONMENT,
                        ACCOUNT,
                        MARKET,
                        SYMBOL,
                        null,
                        "manual-client-1",
                        "12345",
                        OrderResultStatus.ACCEPTED.name(),
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
                        NOW,
                        "evt-external-order"
                )),
                List.of(),
                List.of()
        ));
        return projection;
    }

    private TradingStateProjection projectionWithExternalPositionIntervention() {
        TradingStateProjection projection = new TradingStateProjection();
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(new TradingStateProjection.PositionState(
                        PROVIDER,
                        ENVIRONMENT,
                        ACCOUNT,
                        MARKET,
                        SYMBOL,
                        "BOTH",
                        "0",
                        "50000.00",
                        "50010.00",
                        "0",
                        "USER_DATA",
                        true,
                        "external_position_change",
                        NOW,
                        "evt-external-position"
                )),
                List.of(),
                List.of(),
                List.of()
        ));
        return projection;
    }

    private TradingStateProjection projectionWithUnknownOrderStatus() {
        TradingStateProjection projection = new TradingStateProjection();
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.OrderState(
                        PROVIDER,
                        ENVIRONMENT,
                        ACCOUNT,
                        MARKET,
                        SYMBOL,
                        "cmd-unknown",
                        "tb-lfa-unknown",
                        null,
                        OrderResultStatus.UNKNOWN.name(),
                        null,
                        "50000.00",
                        "0.001",
                        null,
                        null,
                        null,
                        "ORDER_RESULT",
                        null,
                        true,
                        false,
                        null,
                        NOW,
                        "evt-unknown-order"
                )),
                List.of(),
                List.of()
        ));
        return projection;
    }

    private TradingStateProjection projectionWithUnresolvedOrderCommand() {
        TradingStateProjection projection = new TradingStateProjection();
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.OrderState(
                        PROVIDER,
                        ENVIRONMENT,
                        ACCOUNT,
                        MARKET,
                        SYMBOL,
                        "cmd-pending",
                        "tb-lfa-pending",
                        null,
                        "COMMAND_RECEIVED",
                        null,
                        "50000.00",
                        "0.001",
                        null,
                        null,
                        null,
                        "ORDER_COMMAND",
                        null,
                        true,
                        false,
                        null,
                        NOW,
                        "evt-pending-order-command"
                )),
                List.of(),
                List.of()
        ));
        return projection;
    }

    private TradingStateProjection projectionWithProjectedOrder(String commandId, String clientOrderId) {
        TradingStateProjection projection = new TradingStateProjection();
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(new TradingStateProjection.OrderState(
                        PROVIDER,
                        ENVIRONMENT,
                        ACCOUNT,
                        MARKET,
                        SYMBOL,
                        commandId,
                        clientOrderId,
                        "12345",
                        OrderResultStatus.ACCEPTED.name(),
                        "NEW",
                        "50000.00",
                        "0.001",
                        "0",
                        null,
                        null,
                        "ORDER_RESULT",
                        null,
                        true,
                        false,
                        null,
                        NOW,
                        "evt-projected-order"
                )),
                List.of(),
                List.of()
        ));
        return projection;
    }

    private OrderCommandEvent command() {
        return OrderCommandEvent.newBuilder()
                .setEventId("evt-command-001")
                .setSchemaVersion(1)
                .setCommandId("cmd-001")
                .setStrategyId("lfa")
                .setProvider(PROVIDER)
                .setEnvironment(ENVIRONMENT)
                .setAccount(ACCOUNT)
                .setMarket(MARKET)
                .setSymbol(SYMBOL)
                .setSide(OrderCommandSide.BUY)
                .setOrderType(OrderCommandType.LIMIT)
                .setQuantity("0.001")
                .setPrice("50000.00")
                .setReduceOnly(false)
                .setClosePosition(false)
                .setClientOrderId("tb-lfa-001")
                .setIdempotencyKey("idem-001")
                .setRequestedAtMicros(NOW)
                .setAttributes(Map.of("signal_id", "sig-001"))
                .build();
    }

    private OrderCommandEvent commandWithQuantity(String quantity) {
        return OrderCommandEvent.newBuilder(command())
                .setQuantity(quantity)
                .build();
    }

    private OrderCommandEvent commandWithoutPriceOrQuoteQuantity() {
        return OrderCommandEvent.newBuilder(command())
                .setPrice(null)
                .setQuoteOrderQuantity(null)
                .build();
    }
}
