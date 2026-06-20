package io.github.manu.execution;

import io.github.manu.audit.AuditLogger;
import io.github.manu.config.JsonMapperFactory;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.OrderCommandAction;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.OrderResultStatus;
import io.github.manu.events.v1.RiskDecision;
import io.github.manu.events.v1.RiskDecisionEvent;
import io.github.manu.observability.PauseGovernanceMetrics;
import io.github.manu.projection.FileTradingStateProjectionStore;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.projection.TradingStateSnapshot;
import io.github.manu.reconciliation.ReconciliationConfidenceStatus;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import io.github.manu.reconciliation.ReconciliationObservation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OrderRiskGateTest {

    private static final String PROVIDER = "binance";
    private static final String ENVIRONMENT = "demo";
    private static final String ACCOUNT = "main";
    private static final String MARKET = "usd_m_futures";
    private static final String SYMBOL = "BTCUSDT";
    private static final Instant NOW = Instant.parse("2026-05-26T12:00:00Z");

    @TempDir
    private Path temporaryDirectory;

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
        assertThat(decision.getAttributes()).containsEntry("external_order_client_order_ids", "manual-client-1");
        assertThat(decision.getAttributes()).containsEntry("external_order_exchange_order_ids", "12345");
        assertThat(decision.getAttributes()).containsEntry("external_order_intervention_action", "MANUAL_REVIEW");
    }

    @Test
    void requires_manual_review_when_restored_target_has_external_order_intervention_after_restart() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        TradingStateProjection original = projectionWithExternalIntervention();
        FileTradingStateProjectionStore store = new FileTradingStateProjectionStore(
                temporaryDirectory.resolve("projection").resolve("trading-state.json"),
                JsonMapperFactory.create()
        );
        store.save(original.snapshot());
        TradingStateProjection restored = new TradingStateProjection();
        restored.restore(store.load().orElseThrow());

        RiskDecisionEvent decision = gate(defaultProperties(), restored).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("intervention:external_order");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes())
                .containsEntry("external_order_interventions", "1")
                .containsEntry("external_order_client_order_ids", "manual-client-1")
                .containsEntry("external_order_exchange_order_ids", "12345")
                .containsEntry("external_order_intervention_action", "MANUAL_REVIEW");
    }

    @Test
    void requires_manual_review_when_target_has_external_position_intervention() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithExternalPositionIntervention()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("intervention:external_position");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes()).containsEntry("external_position_interventions", "1");
        assertThat(decision.getAttributes()).containsEntry("external_position_symbols", SYMBOL);
        assertThat(decision.getAttributes()).containsEntry("external_position_sides", "BOTH");
        assertThat(decision.getAttributes()).containsEntry("external_position_amounts", "0");
        assertThat(decision.getAttributes()).containsEntry("external_position_update_sources", "USER_DATA");
        assertThat(decision.getAttributes()).containsEntry(
                "external_position_intervention_reasons",
                "external_position_change"
        );
        assertThat(decision.getAttributes()).containsEntry("external_position_intervention_action", "MANUAL_REVIEW");
    }

    @Test
    void requires_manual_review_when_restored_target_has_external_position_intervention_after_restart() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        TradingStateProjection original = projectionWithExternalPositionIntervention();
        FileTradingStateProjectionStore store = new FileTradingStateProjectionStore(
                temporaryDirectory.resolve("projection").resolve("trading-state.json"),
                JsonMapperFactory.create()
        );
        store.save(original.snapshot());
        TradingStateProjection restored = new TradingStateProjection();
        restored.restore(store.load().orElseThrow());

        RiskDecisionEvent decision = gate(defaultProperties(), restored).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("intervention:external_position");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes())
                .containsEntry("external_position_interventions", "1")
                .containsEntry("external_position_symbols", SYMBOL)
                .containsEntry("external_position_sides", "BOTH")
                .containsEntry("external_position_amounts", "0")
                .containsEntry("external_position_update_sources", "USER_DATA")
                .containsEntry("external_position_intervention_reasons", "external_position_change")
                .containsEntry("external_position_intervention_action", "MANUAL_REVIEW");
    }

    @Test
    void requires_manual_review_when_target_has_unknown_order_status() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithUnknownOrderStatus()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_status:unknown");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes()).containsEntry("unknown_order_statuses", "1");
        assertThat(decision.getAttributes()).containsEntry("unknown_order_command_ids", "cmd-unknown");
        assertThat(decision.getAttributes()).containsEntry("unknown_order_client_order_ids", "tb-lfa-unknown");
        assertThat(decision.getAttributes()).containsEntry("unknown_order_status_action", "MANUAL_REVIEW");
    }

    @Test
    void requires_manual_review_when_restored_target_has_unknown_order_status_after_restart() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        TradingStateProjection original = projectionWithUnknownOrderStatus();
        FileTradingStateProjectionStore store = new FileTradingStateProjectionStore(
                temporaryDirectory.resolve("projection").resolve("trading-state.json"),
                JsonMapperFactory.create()
        );
        store.save(original.snapshot());
        TradingStateProjection restored = new TradingStateProjection();
        restored.restore(store.load().orElseThrow());

        RiskDecisionEvent decision = gate(defaultProperties(), restored).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_status:unknown");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes())
                .containsEntry("unknown_order_statuses", "1")
                .containsEntry("unknown_order_command_ids", "cmd-unknown")
                .containsEntry("unknown_order_client_order_ids", "tb-lfa-unknown")
                .containsEntry("unknown_order_status_action", "MANUAL_REVIEW");
    }

    @Test
    void requires_manual_review_when_target_has_unresolved_order_command() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithUnresolvedOrderCommand()).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_command:unresolved");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes()).containsEntry("unresolved_order_commands", "1");
        assertThat(decision.getAttributes()).containsEntry("unresolved_order_command_ids", "cmd-pending");
        assertThat(decision.getAttributes()).containsEntry("unresolved_order_client_order_ids", "tb-lfa-pending");
        assertThat(decision.getAttributes()).containsEntry("pending_order_command_action", "MANUAL_REVIEW");
    }

    @Test
    void requires_manual_review_when_restored_target_has_unresolved_order_command_after_restart() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        TradingStateProjection original = projectionWithUnresolvedOrderCommand();
        FileTradingStateProjectionStore store = new FileTradingStateProjectionStore(
                temporaryDirectory.resolve("projection").resolve("trading-state.json"),
                JsonMapperFactory.create()
        );
        store.save(original.snapshot());
        TradingStateProjection restored = new TradingStateProjection();
        restored.restore(store.load().orElseThrow());

        RiskDecisionEvent decision = gate(defaultProperties(), restored).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_command:unresolved");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes())
                .containsEntry("unresolved_order_commands", "1")
                .containsEntry("unresolved_order_command_ids", "cmd-pending")
                .containsEntry("unresolved_order_client_order_ids", "tb-lfa-pending")
                .containsEntry("pending_order_command_action", "MANUAL_REVIEW");
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
    void rejects_new_order_when_account_is_paused_by_governance() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithPause("ACCOUNT", ACCOUNT, null, "PAUSE_ACCOUNT"))
                .evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("pause_governance:account");
        assertThat(decision.getAttributes())
                .containsEntry("pause_governance_active", "true")
                .containsEntry("pause_governance_account_paused", "true")
                .containsEntry("pause_governance_symbol_paused", "false")
                .containsEntry("pause_governance_scopes", "ACCOUNT")
                .containsEntry("pause_governance_targets", ACCOUNT)
                .containsEntry("pause_governance_actions", "PAUSE_ACCOUNT");
    }

    @Test
    void rejects_new_order_when_symbol_is_paused_by_governance() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithPause("SYMBOL", SYMBOL, SYMBOL, "PAUSE_SYMBOL"))
                .evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("pause_governance:symbol");
        assertThat(decision.getAttributes())
                .containsEntry("pause_governance_active", "true")
                .containsEntry("pause_governance_account_paused", "false")
                .containsEntry("pause_governance_symbol_paused", "true")
                .containsEntry("pause_governance_scopes", "SYMBOL")
                .containsEntry("pause_governance_targets", SYMBOL)
                .containsEntry("pause_governance_actions", "PAUSE_SYMBOL");
    }

    @Test
    void rejects_new_order_when_restored_symbol_pause_governance_is_active_after_restart() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        TradingStateProjection original = projectionWithPause("SYMBOL", SYMBOL, SYMBOL, "PAUSE_SYMBOL");
        FileTradingStateProjectionStore store = new FileTradingStateProjectionStore(
                temporaryDirectory.resolve("projection").resolve("trading-state.json"),
                JsonMapperFactory.create()
        );
        store.save(original.snapshot());
        TradingStateProjection restored = new TradingStateProjection();
        restored.restore(store.load().orElseThrow());

        RiskDecisionEvent decision = gate(defaultProperties(), restored).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("pause_governance:symbol");
        assertThat(decision.getAttributes())
                .containsEntry("pause_governance_active", "true")
                .containsEntry("pause_governance_symbol_paused", "true")
                .containsEntry("pause_governance_targets", SYMBOL)
                .containsEntry("pause_governance_remediation_ids", "remediation-pause-001");
    }

    @Test
    void approves_new_order_when_symbol_pause_governance_has_expired() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithPause(
                "SYMBOL",
                SYMBOL,
                SYMBOL,
                "PAUSE_SYMBOL",
                Map.of("pause_expires_at", NOW.minusSeconds(1).toString())
        )).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getAttributes()).containsEntry("pause_governance_active", "false");
    }

    @Test
    void rejects_pause_override_when_override_policy_is_disabled_by_default() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithPause("SYMBOL", SYMBOL, SYMBOL, "PAUSE_SYMBOL"))
                .evaluate(commandWithAttributes(Map.of(
                        "pause_override",
                        "true",
                        "pause_override_by",
                        "operator",
                        "pause_override_reason",
                        "controlled test order",
                        "pause_override_expires_at",
                        NOW.plusSeconds(60).toString()
                )));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly(
                "pause_governance:symbol",
                "pause_governance:override_invalid"
        );
        assertThat(decision.getAttributes())
                .containsEntry("pause_override_enabled", "false")
                .containsEntry("pause_override_requested", "true")
                .containsEntry("pause_override_allowed", "false")
                .containsEntry("pause_override_invalid_reason", "policy_disabled");
    }

    @Test
    void approves_new_order_when_pause_override_policy_allows_audited_time_bounded_override() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(propertiesWithPauseOverride(), projectionWithPause("SYMBOL", SYMBOL, SYMBOL, "PAUSE_SYMBOL"))
                .evaluate(commandWithAttributes(Map.of(
                        "pause_override",
                        "true",
                        "pause_override_by",
                        "operator",
                        "pause_override_reason",
                        "controlled test order",
                        "pause_override_expires_at",
                        NOW.plusSeconds(60).toString()
                )));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("pause_governance:override", "risk_gate:approved");
        assertThat(decision.getAttributes())
                .containsEntry("pause_override_enabled", "true")
                .containsEntry("pause_override_requested", "true")
                .containsEntry("pause_override_allowed", "true")
                .containsEntry("pause_override_by", "operator")
                .containsEntry("pause_override_reason", "controlled test order")
                .containsEntry("pause_override_expires_at", NOW.plusSeconds(60).toString());
    }

    @Test
    void audits_pause_override_attempt_after_risk_decision_is_built() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        AuditLogger auditLogger = mock(AuditLogger.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OrderCommandEvent command = commandWithAttributes(Map.of(
                "pause_override",
                "true",
                "pause_override_by",
                "operator",
                "pause_override_reason",
                "controlled test order",
                "pause_override_expires_at",
                NOW.plusSeconds(60).toString()
        ));

        RiskDecisionEvent decision = new OrderRiskGate(
                propertiesWithPauseOverride(),
                reconciliationTracker,
                projectionWithPause("SYMBOL", SYMBOL, SYMBOL, "PAUSE_SYMBOL"),
                auditLogger,
                new PauseGovernanceMetrics(meterRegistry),
                clock
        ).evaluate(command);

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(meterRegistry.getMeters()
                .stream()
                .filter(meter -> "trading.pause_governance.override.events".equals(meter.getId().getName()))
                .toList())
                .singleElement()
                .satisfies(meter -> {
                    assertThat(meter.getId().getTag("provider")).isEqualTo("binance");
                    assertThat(meter.getId().getTag("environment")).isEqualTo("demo");
                    assertThat(meter.getId().getTag("account")).isEqualTo("main");
                    assertThat(meter.getId().getTag("market")).isEqualTo("usd_m_futures");
                    assertThat(meter.getId().getTag("symbol")).isEqualTo("BTCUSDT");
                    assertThat(meter.getId().getTag("decision")).isEqualTo("APPROVED");
                    assertThat(meter.getId().getTag("outcome")).isEqualTo("allowed");
                    assertThat(meter.getId().getTag("invalid_reason")).isEqualTo("none");
                    assertThat(meterRegistry.get(meter.getId().getName()).tags(meter.getId().getTags()).counter().count())
                            .isEqualTo(1.0d);
                });
        verify(auditLogger).pauseOverrideEvaluated(any(OrderCommandEvent.class), any(RiskDecisionEvent.class));
    }

    @Test
    void rejects_pause_override_when_override_expiry_exceeds_policy_window() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(propertiesWithPauseOverride(), projectionWithPause("SYMBOL", SYMBOL, SYMBOL, "PAUSE_SYMBOL"))
                .evaluate(commandWithAttributes(Map.of(
                        "pause_override",
                        "true",
                        "pause_override_by",
                        "operator",
                        "pause_override_reason",
                        "controlled test order",
                        "pause_override_expires_at",
                        NOW.plusSeconds(901).toString()
                )));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly(
                "pause_governance:symbol",
                "pause_governance:override_invalid"
        );
        assertThat(decision.getAttributes())
                .containsEntry("pause_override_allowed", "false")
                .containsEntry("pause_override_invalid_reason", "expiry_exceeds_policy");
    }

    @Test
    void approves_cancel_when_symbol_is_paused_by_governance() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithTargetOrderAndPause())
                .evaluate(cancelCommand("tb-lfa-open"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getAttributes())
                .containsEntry("pause_governance_active", "true")
                .containsEntry("pause_governance_symbol_paused", "true")
                .containsEntry("pause_governance_allows_cancel", "true")
                .containsEntry("target_client_order_id", "tb-lfa-open");
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
    void approves_quantity_bounded_reduce_only_command_before_provider_mapping() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties()).evaluate(reduceOnlyCommand());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getMaxQuantity()).isEqualTo("0.001");
    }

    @Test
    void rejects_reduce_only_quote_notional_command_before_provider_mapping() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties()).evaluate(OrderCommandEvent.newBuilder(reduceOnlyCommand())
                .setQuantity(null)
                .setQuoteOrderQuantity("100")
                .setPrice(null)
                .build());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("order_limit:reduce_only_unbounded_quantity");
    }

    @Test
    void approves_unsized_close_position_command_before_provider_mapping() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties()).evaluate(OrderCommandEvent.newBuilder(reduceOnlyCommand())
                .setOrderType(OrderCommandType.STOP_MARKET)
                .setQuantity(null)
                .setPrice(null)
                .setStopPrice("49000.00")
                .setReduceOnly(true)
                .setClosePosition(true)
                .build());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getMaxQuantity()).isNull();
    }

    @Test
    void rejects_sized_close_position_command_before_provider_mapping() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties()).evaluate(OrderCommandEvent.newBuilder(reduceOnlyCommand())
                .setOrderType(OrderCommandType.STOP_MARKET)
                .setStopPrice("49000.00")
                .setReduceOnly(true)
                .setClosePosition(true)
                .build());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("order_limit:close_position_sized");
    }

    @Test
    void rejects_new_order_when_projected_open_order_count_reaches_configured_limit() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        ExecutionProperties properties = propertiesWithOrderLimit(new ExecutionProperties.OrderLimit(
                true,
                true,
                null,
                null,
                true,
                1,
                ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS
        ));

        RiskDecisionEvent decision = gate(properties, projectionWithTargetOrder(
                "tb-lfa-open",
                OrderResultStatus.ACCEPTED.name(),
                true,
                false
        )).evaluate(command());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("order_limit:max_open_orders");
        assertThat(decision.getAttributes())
                .containsEntry("order_limit_max_open_orders", "1")
                .containsEntry("order_limit_open_orders", "1");
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

    @Test
    void requires_manual_review_for_cancel_without_target_order_id() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties()).evaluate(cancelCommand(null));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_target:missing_order_id");
        assertThat(decision.getAttributes())
                .containsEntry("target_order_policy_enabled", "true")
                .containsEntry("target_order_require_order_id", "true")
                .containsEntry("target_order_require_client_order_id", "false")
                .containsEntry("target_order_action", "MANUAL_REVIEW");
    }

    @Test
    void requires_manual_review_for_cancel_when_projected_target_is_missing() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties()).evaluate(cancelCommand("tb-lfa-open"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_target:not_projected");
        assertThat(decision.getAttributes()).containsEntry("target_client_order_id", "tb-lfa-open");
    }

    @Test
    void approves_cancel_for_projected_managed_open_target_order() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithTargetOrder(
                "tb-lfa-open",
                OrderResultStatus.ACCEPTED.name(),
                true,
                false
        )).evaluate(cancelCommand("tb-lfa-open"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes())
                .containsEntry("target_client_order_id", "tb-lfa-open")
                .containsEntry("target_order_status", "ACCEPTED")
                .containsEntry("target_order_managed_by_bot", "true");
    }

    @Test
    void approves_cancel_for_projected_managed_open_target_order_by_exchange_order_id() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithTargetOrder(
                "tb-lfa-open",
                "12345",
                OrderResultStatus.ACCEPTED.name(),
                true,
                false
        )).evaluate(cancelCommandByExchangeOrderId("12345"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getMaxQuantity()).isNull();
        assertThat(decision.getAttributes())
                .containsEntry("target_exchange_order_id", "12345")
                .containsEntry("target_projected_client_order_id", "tb-lfa-open")
                .containsEntry("target_projected_exchange_order_id", "12345")
                .containsEntry("target_order_status", "ACCEPTED")
                .containsEntry("target_order_managed_by_bot", "true");
    }

    @Test
    void approves_cancel_when_other_external_order_intervention_exists_by_default() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithTargetAndExternalIntervention())
                .evaluate(cancelCommand("tb-lfa-open"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getAttributes())
                .containsEntry("external_order_interventions", "1")
                .containsEntry("external_order_intervention_apply_to_target_commands", "false")
                .containsEntry("target_client_order_id", "tb-lfa-open");
    }

    @Test
    void requires_manual_review_for_cancel_when_external_order_policy_applies_to_target_commands() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        ExecutionProperties properties = new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true),
                new ExecutionProperties.ManualIntervention(
                        true,
                        true,
                        ExecutionProperties.InterventionAction.MANUAL_REVIEW,
                        ExecutionProperties.InterventionAction.MANUAL_REVIEW,
                        true,
                        false
                )
        ));

        RiskDecisionEvent decision = gate(properties, projectionWithTargetAndExternalIntervention())
                .evaluate(cancelCommand("tb-lfa-open"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("intervention:external_order");
        assertThat(decision.getAttributes()).containsEntry("external_order_intervention_apply_to_target_commands", "true");
    }

    @Test
    void approves_cancel_when_unknown_or_pending_order_exists_by_default() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent unknownDecision = gate(defaultProperties(), projectionWithTargetAndUnknownOrderStatus())
                .evaluate(cancelCommand("tb-lfa-open"));
        RiskDecisionEvent pendingDecision = gate(defaultProperties(), projectionWithTargetAndUnresolvedOrderCommand())
                .evaluate(cancelCommand("tb-lfa-open"));

        assertThat(unknownDecision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(unknownDecision.getAttributes())
                .containsEntry("unknown_order_statuses", "1")
                .containsEntry("unknown_order_status_apply_to_target_commands", "false");
        assertThat(pendingDecision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(pendingDecision.getAttributes())
                .containsEntry("unresolved_order_commands", "1")
                .containsEntry("pending_order_command_apply_to_target_commands", "false");
    }

    @Test
    void requires_manual_review_for_cancel_when_unknown_status_policy_applies_to_target_commands() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        ExecutionProperties properties = new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true),
                null,
                new ExecutionProperties.UnknownOrderStatus(
                        true,
                        ExecutionProperties.InterventionAction.MANUAL_REVIEW,
                        true
                )
        ));

        RiskDecisionEvent decision = gate(properties, projectionWithTargetAndUnknownOrderStatus())
                .evaluate(cancelCommand("tb-lfa-open"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_status:unknown");
        assertThat(decision.getAttributes()).containsEntry("unknown_order_status_apply_to_target_commands", "true");
    }

    @Test
    void requires_manual_review_for_cancel_when_pending_command_policy_applies_to_target_commands() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        ExecutionProperties properties = new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true),
                null,
                null,
                new ExecutionProperties.PendingOrderCommand(
                        true,
                        ExecutionProperties.InterventionAction.MANUAL_REVIEW,
                        true
                )
        ));

        RiskDecisionEvent decision = gate(properties, projectionWithTargetAndUnresolvedOrderCommand())
                .evaluate(cancelCommand("tb-lfa-open"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_command:unresolved");
        assertThat(decision.getAttributes()).containsEntry("pending_order_command_apply_to_target_commands", "true");
    }

    @Test
    void requires_manual_review_when_target_identifiers_conflict_with_projection() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithTargetOrder(
                "tb-lfa-open",
                "12345",
                OrderResultStatus.ACCEPTED.name(),
                true,
                false
        )).evaluate(OrderCommandEvent.newBuilder(cancelCommand("tb-lfa-open"))
                .setTargetExchangeOrderId("67890")
                .build());

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_target:identity_mismatch");
        assertThat(decision.getAttributes())
                .containsEntry("target_client_order_id", "tb-lfa-open")
                .containsEntry("target_exchange_order_id", "67890")
                .containsEntry("target_projected_exchange_order_id", "12345");
    }

    @Test
    void requires_manual_review_for_modify_when_projected_target_is_closed() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithTargetOrder(
                "tb-lfa-closed",
                OrderResultStatus.CANCELED.name(),
                true,
                false
        )).evaluate(modifyCommand("tb-lfa-closed"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_target:closed");
        assertThat(decision.getAttributes())
                .containsEntry("target_client_order_id", "tb-lfa-closed")
                .containsEntry("target_order_status", "CANCELED");
    }

    @Test
    void rejects_cancel_for_unmanaged_projected_target_when_configured_to_reject() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        ExecutionProperties properties = new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true),
                null,
                null,
                null,
                null,
                new ExecutionProperties.TargetOrder(
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS
                )
        ));

        RiskDecisionEvent decision = gate(properties, projectionWithTargetOrder(
                "manual-client-1",
                OrderResultStatus.ACCEPTED.name(),
                false,
                false
        )).evaluate(cancelCommand("manual-client-1"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(decision.getReasons()).containsExactly("order_target:not_managed");
        assertThat(decision.getAttributes())
                .containsEntry("target_order_action", "REJECT_NEW_COMMANDS")
                .containsEntry("target_order_managed_by_bot", "false");
    }

    @Test
    void approves_external_remediation_cancel_for_unmanaged_external_target_when_allowed() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithTargetOrder(
                "manual-client-1",
                OrderResultStatus.ACCEPTED.name(),
                false,
                true
        )).evaluate(externalRemediationCancelCommand("manual-client-1"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getAttributes())
                .containsEntry("target_order_allow_external_remediation_cancel", "true")
                .containsEntry("target_order_external_remediation_cancel", "true")
                .containsEntry("target_order_managed_by_bot", "false")
                .containsEntry("target_order_external_intervention", "true");
    }

    @Test
    void requires_manual_review_for_adopted_target_order_by_default() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithAdoptedTargetOrder("adopted-client-1"))
                .evaluate(cancelCommand("adopted-client-1"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_target:adopted_not_allowed");
        assertThat(decision.getAttributes())
                .containsEntry("target_order_allow_adopted_target_orders", "false")
                .containsEntry("target_order_adopted", "true")
                .containsEntry("target_order_managed_by_bot", "true");
    }

    @Test
    void approves_adopted_target_order_when_policy_allows_it() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);
        ExecutionProperties properties = new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true),
                null,
                null,
                null,
                null,
                new ExecutionProperties.TargetOrder(
                        true,
                        false,
                        true,
                        true,
                        true,
                        true,
                        ExecutionProperties.InterventionAction.MANUAL_REVIEW,
                        true,
                        true,
                        true
                )
        ));

        RiskDecisionEvent decision = gate(properties, projectionWithAdoptedTargetOrder("adopted-client-1"))
                .evaluate(cancelCommand("adopted-client-1"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getAttributes())
                .containsEntry("target_order_allow_adopted_target_orders", "true")
                .containsEntry("target_order_adopted", "true");
    }

    @Test
    void approves_managed_remediation_amend_for_managed_external_intervention_target() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithTargetOrder(
                "tb-lfa-open",
                OrderResultStatus.ACCEPTED.name(),
                true,
                true
        )).evaluate(managedRemediationAmendCommand("tb-lfa-open"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getAttributes())
                .containsEntry("target_order_managed_remediation_amend", "true")
                .containsEntry("target_order_external_intervention", "true")
                .containsEntry("target_order_managed_by_bot", "true");
    }

    @Test
    void requires_manual_review_for_adopted_managed_remediation_amend_without_lifecycle_policy() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithAdoptedTargetOrder("adopted-client-1"))
                .evaluate(managedRemediationAmendCommand("adopted-client-1", true));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.MANUAL_REVIEW);
        assertThat(decision.getReasons()).containsExactly("order_target:adopted_not_allowed");
        assertThat(decision.getAttributes())
                .containsEntry("target_order_managed_remediation_amend", "true")
                .containsEntry("target_order_adopted", "true")
                .containsEntry("target_order_allow_adopted_target_orders", "false");
    }

    @Test
    void approves_adopted_managed_remediation_amend_when_amendment_and_lifecycle_policies_allow_adoption() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithAdoptedTargetOrder("adopted-client-1"))
                .evaluate(managedRemediationAmendCommand("adopted-client-1", true, true));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getAttributes())
                .containsEntry("target_order_managed_remediation_amend", "true")
                .containsEntry("target_order_adopted", "true")
                .containsEntry("target_order_allow_adopted_target_orders", "false");
    }

    @Test
    void approves_adopted_remediation_cancel_when_lifecycle_policy_allows_cancel() {
        recordReconciliation(ReconciliationConfidenceStatus.CONFIDENT);

        RiskDecisionEvent decision = gate(defaultProperties(), projectionWithAdoptedTargetOrder("adopted-client-1"))
                .evaluate(adoptedRemediationCancelCommand("adopted-client-1"));

        assertThat(decision.getDecision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(decision.getReasons()).containsExactly("risk_gate:approved");
        assertThat(decision.getAttributes())
                .containsEntry("target_order_external_remediation_cancel", "true")
                .containsEntry("target_order_adopted", "true")
                .containsEntry("target_order_allow_adopted_target_orders", "false");
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

    private ExecutionProperties propertiesWithPauseOverride() {
        return new ExecutionProperties(new ExecutionProperties.RiskGate(
                true,
                new ExecutionProperties.Reconciliation(false, true, true),
                null,
                null,
                null,
                null,
                null,
                new ExecutionProperties.PauseGovernance(true, true, true, true, 900)
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
                        "5",
                        "cross",
                        null,
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

    private TradingStateProjection projectionWithTargetOrder(
            String clientOrderId,
            String status,
            boolean managedByBot,
            boolean externalIntervention
    ) {
        return projectionWithTargetOrder(clientOrderId, "12345", status, managedByBot, externalIntervention);
    }

    private TradingStateProjection projectionWithTargetOrder(
            String clientOrderId,
            String exchangeOrderId,
            String status,
            boolean managedByBot,
            boolean externalIntervention
    ) {
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
                        "cmd-target",
                        clientOrderId,
                        exchangeOrderId,
                        status,
                        "NEW",
                        "50000.00",
                        "0.001",
                        "0",
                        null,
                        null,
                        "ORDER_RESULT",
                        null,
                        managedByBot,
                        externalIntervention,
                        externalIntervention ? "external_order_observed" : null,
                        NOW,
                        "evt-target-order"
                )),
                List.of(),
                List.of()
        ));
        return projection;
    }

    private TradingStateProjection projectionWithAdoptedTargetOrder(String clientOrderId) {
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
                        clientOrderId,
                        "12345",
                        OrderResultStatus.ACCEPTED.name(),
                        "NEW",
                        "50000.00",
                        "0.001",
                        "0",
                        null,
                        null,
                        "INTERVENTION_ADOPTION",
                        "NEW",
                        true,
                        false,
                        null,
                        NOW,
                        "evt-adopted-order"
                )),
                List.of(),
                List.of()
        ));
        return projection;
    }

    private TradingStateProjection projectionWithTargetAndExternalIntervention() {
        TradingStateProjection projection = new TradingStateProjection();
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(
                        targetOrderState("tb-lfa-open", "12345", OrderResultStatus.ACCEPTED.name(), true, false),
                        targetOrderState("manual-client-1", "67890", OrderResultStatus.ACCEPTED.name(), false, true)
                ),
                List.of(),
                List.of()
        ));
        return projection;
    }

    private TradingStateProjection projectionWithTargetAndUnknownOrderStatus() {
        TradingStateProjection projection = new TradingStateProjection();
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(
                        targetOrderState("tb-lfa-open", "12345", OrderResultStatus.ACCEPTED.name(), true, false),
                        targetOrderState("tb-lfa-unknown", "67890", OrderResultStatus.UNKNOWN.name(), true, false)
                ),
                List.of(),
                List.of()
        ));
        return projection;
    }

    private TradingStateProjection projectionWithTargetAndUnresolvedOrderCommand() {
        TradingStateProjection projection = new TradingStateProjection();
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(
                        targetOrderState("tb-lfa-open", "12345", OrderResultStatus.ACCEPTED.name(), true, false),
                        unresolvedOrderCommandState()
                ),
                List.of(),
                List.of()
        ));
        return projection;
    }

    private TradingStateProjection projectionWithPause(String scope, String target, String symbol, String action) {
        return projectionWithPause(scope, target, symbol, action, Map.of());
    }

    private TradingStateProjection projectionWithPause(
            String scope,
            String target,
            String symbol,
            String action,
            Map<String, String> attributes
    ) {
        TradingStateProjection projection = new TradingStateProjection();
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(pauseGovernanceState(scope, target, symbol, action, attributes)),
                List.of()
        ));
        return projection;
    }

    private TradingStateProjection projectionWithTargetOrderAndPause() {
        TradingStateProjection projection = new TradingStateProjection();
        projection.restore(new TradingStateSnapshot(
                List.of(),
                List.of(),
                List.of(targetOrderState("tb-lfa-open", "12345", OrderResultStatus.ACCEPTED.name(), true, false)),
                List.of(),
                List.of(),
                List.of(),
                List.of(pauseGovernanceState("SYMBOL", SYMBOL, SYMBOL, "PAUSE_SYMBOL", Map.of())),
                List.of()
        ));
        return projection;
    }

    private TradingStateProjection.PauseGovernanceState pauseGovernanceState(
            String scope,
            String target,
            String symbol,
            String action,
            Map<String, String> attributes
    ) {
        return new TradingStateProjection.PauseGovernanceState(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                scope,
                target,
                symbol,
                "remediation-pause-001",
                "POSITION",
                action,
                "external_position_change",
                List.of("risk_policy"),
                "automated_policy",
                "pause until risk is resolved",
                attributes,
                true,
                NOW,
                "evt-pause"
        );
    }

    private TradingStateProjection.OrderState unresolvedOrderCommandState() {
        return new TradingStateProjection.OrderState(
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
        );
    }

    private TradingStateProjection.OrderState targetOrderState(
            String clientOrderId,
            String exchangeOrderId,
            String status,
            boolean managedByBot,
            boolean externalIntervention
    ) {
        return new TradingStateProjection.OrderState(
                PROVIDER,
                ENVIRONMENT,
                ACCOUNT,
                MARKET,
                SYMBOL,
                externalIntervention ? null : "cmd-target",
                clientOrderId,
                exchangeOrderId,
                status,
                "NEW",
                "50000.00",
                "0.001",
                "0",
                null,
                null,
                externalIntervention ? "USER_DATA" : "ORDER_RESULT",
                externalIntervention ? "NEW" : null,
                managedByBot,
                externalIntervention,
                externalIntervention ? "external_order_observed" : null,
                NOW,
                externalIntervention ? "evt-external-order" : "evt-target-order"
        );
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

    private OrderCommandEvent commandWithAttributes(Map<String, String> attributes) {
        Map<CharSequence, CharSequence> avroAttributes = new java.util.LinkedHashMap<>();
        avroAttributes.putAll(attributes);
        return OrderCommandEvent.newBuilder(command())
                .setAttributes(avroAttributes)
                .build();
    }

    private OrderCommandEvent reduceOnlyCommand() {
        return OrderCommandEvent.newBuilder(command())
                .setSide(OrderCommandSide.SELL)
                .setReduceOnly(true)
                .build();
    }

    private OrderCommandEvent cancelCommand(String targetClientOrderId) {
        return OrderCommandEvent.newBuilder(command())
                .setAction(OrderCommandAction.CANCEL)
                .setCommandId("cmd-cancel-001")
                .setClientOrderId("tb-cancel-001")
                .setTargetClientOrderId(targetClientOrderId)
                .build();
    }

    private OrderCommandEvent cancelCommandByExchangeOrderId(String targetExchangeOrderId) {
        return OrderCommandEvent.newBuilder(command())
                .setAction(OrderCommandAction.CANCEL)
                .setCommandId("cmd-cancel-001")
                .setClientOrderId("tb-cancel-001")
                .setTargetClientOrderId(null)
                .setTargetExchangeOrderId(targetExchangeOrderId)
                .build();
    }

    private OrderCommandEvent externalRemediationCancelCommand(String targetClientOrderId) {
        return OrderCommandEvent.newBuilder(cancelCommand(targetClientOrderId))
                .setCommandId("remediation-command:remediation-001:cancel-order")
                .setClientOrderId("rm-cxl-remediation-001")
                .setAttributes(Map.of(
                        "command_source", "intervention_remediation_executor",
                        "remediation_id", "remediation-001",
                        "remediation_scope", "ORDER",
                        "remediation_action", "CLOSE",
                        "remediation_operation", "CANCEL_ORDER",
                        "target_client_order_id", targetClientOrderId
                ))
                .build();
    }

    private OrderCommandEvent adoptedRemediationCancelCommand(String targetClientOrderId) {
        return OrderCommandEvent.newBuilder(cancelCommand(targetClientOrderId))
                .setCommandId("remediation-command:remediation-001:cancel-order")
                .setClientOrderId("rm-cxl-remediation-001")
                .setAttributes(Map.of(
                        "command_source", "intervention_remediation_executor",
                        "remediation_id", "remediation-001",
                        "remediation_scope", "ORDER",
                        "remediation_action", "CLOSE",
                        "remediation_operation", "CANCEL_ORDER",
                        "adopted_order_ownership", "ADOPTED",
                        "adopted_order_lifecycle_allow_cancel", "true",
                        "target_client_order_id", targetClientOrderId
                ))
                .build();
    }

    private OrderCommandEvent managedRemediationAmendCommand(String targetClientOrderId) {
        return managedRemediationAmendCommand(targetClientOrderId, false);
    }

    private OrderCommandEvent managedRemediationAmendCommand(String targetClientOrderId, boolean adoptedAllowed) {
        return managedRemediationAmendCommand(targetClientOrderId, adoptedAllowed, false);
    }

    private OrderCommandEvent managedRemediationAmendCommand(
            String targetClientOrderId,
            boolean adoptedAllowed,
            boolean adoptedLifecycleAllowed
    ) {
        return OrderCommandEvent.newBuilder(modifyCommand(targetClientOrderId))
                .setCommandId("remediation-command:remediation-001:amend-order")
                .setClientOrderId("rm-amd-remediation-001")
                .setAttributes(Map.of(
                        "command_source", "intervention_remediation_executor",
                        "remediation_id", "remediation-001",
                        "remediation_scope", "ORDER",
                        "remediation_action", "AMEND",
                        "remediation_operation", "AMEND_ORDER",
                        "amendment_execution_mode", "managed_order_modify",
                        "amendment_order_ownership", adoptedAllowed ? "ADOPTED" : "BOT_CREATED",
                        "managed_order_amendment_allow_adopted_orders", Boolean.toString(adoptedAllowed),
                        "adopted_order_lifecycle_allow_amend", Boolean.toString(adoptedLifecycleAllowed),
                        "target_client_order_id", targetClientOrderId
                ))
                .build();
    }

    private OrderCommandEvent modifyCommand(String targetClientOrderId) {
        return OrderCommandEvent.newBuilder(command())
                .setAction(OrderCommandAction.MODIFY)
                .setCommandId("cmd-modify-001")
                .setClientOrderId("tb-modify-001")
                .setTargetClientOrderId(targetClientOrderId)
                .setQuantity("0.002")
                .setPrice("50100.00")
                .build();
    }
}
