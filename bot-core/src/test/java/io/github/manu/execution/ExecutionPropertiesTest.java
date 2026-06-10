package io.github.manu.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionPropertiesTest {

    @Test
    void defaults_manual_intervention_to_manual_review_actions() {
        ExecutionProperties properties = new ExecutionProperties(null);
        ExecutionProperties.ManualIntervention manualIntervention = properties.riskGate().manualIntervention();

        assertThat(manualIntervention.rejectExternalOrderInterventions()).isTrue();
        assertThat(manualIntervention.rejectExternalPositionInterventions()).isTrue();
        assertThat(manualIntervention.externalOrderAction())
                .isEqualTo(ExecutionProperties.InterventionAction.MANUAL_REVIEW);
        assertThat(manualIntervention.externalOrderApplyToTargetCommands()).isFalse();
        assertThat(manualIntervention.externalPositionAction())
                .isEqualTo(ExecutionProperties.InterventionAction.MANUAL_REVIEW);
        assertThat(manualIntervention.externalPositionApplyToTargetCommands()).isFalse();
        assertThat(properties.riskGate().unknownOrderStatus().rejectUnknownOrderStatus()).isTrue();
        assertThat(properties.riskGate().unknownOrderStatus().action())
                .isEqualTo(ExecutionProperties.InterventionAction.MANUAL_REVIEW);
        assertThat(properties.riskGate().unknownOrderStatus().applyToTargetCommands()).isFalse();
        assertThat(properties.riskGate().pendingOrderCommand().rejectUnresolvedOrderCommands()).isTrue();
        assertThat(properties.riskGate().pendingOrderCommand().action())
                .isEqualTo(ExecutionProperties.InterventionAction.MANUAL_REVIEW);
        assertThat(properties.riskGate().pendingOrderCommand().applyToTargetCommands()).isFalse();
        assertThat(properties.riskGate().orderLimit().enabled()).isTrue();
        assertThat(properties.riskGate().orderLimit().rejectInvalidNumericFields()).isTrue();
        assertThat(properties.riskGate().orderLimit().rejectUnboundedNotional()).isTrue();
        assertThat(properties.riskGate().orderLimit().action())
                .isEqualTo(ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS);
        assertThat(properties.riskGate().orderLimit().targetLimits()).isEmpty();
        assertThat(properties.riskGate().targetOrder().allowExternalRemediationCancel()).isTrue();
        assertThat(properties.riskGate().targetOrder().allowAdoptedTargetOrders()).isFalse();
        assertThat(properties.idempotency().rejectProjectedDuplicates()).isTrue();
    }

    @Test
    void derives_allow_action_from_legacy_false_reject_flag() {
        ExecutionProperties.ManualIntervention manualIntervention =
                new ExecutionProperties.ManualIntervention(false, false);

        assertThat(manualIntervention.rejectExternalOrderInterventions()).isFalse();
        assertThat(manualIntervention.rejectExternalPositionInterventions()).isFalse();
        assertThat(manualIntervention.externalOrderAction())
                .isEqualTo(ExecutionProperties.InterventionAction.ALLOW_NEW_COMMANDS);
        assertThat(manualIntervention.externalPositionAction())
                .isEqualTo(ExecutionProperties.InterventionAction.ALLOW_NEW_COMMANDS);
    }

    @Test
    void rejects_conflicting_legacy_flag_and_remediation_action() {
        assertThatThrownBy(() -> new ExecutionProperties.ManualIntervention(
                        false,
                        true,
                        ExecutionProperties.InterventionAction.MANUAL_REVIEW,
                        ExecutionProperties.InterventionAction.MANUAL_REVIEW
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("manual intervention reject flag conflicts with remediation action");
    }

    @Test
    void rejects_conflicting_unknown_order_status_flag_and_action() {
        assertThatThrownBy(() -> new ExecutionProperties.UnknownOrderStatus(
                        false,
                        ExecutionProperties.InterventionAction.MANUAL_REVIEW
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown order status reject flag conflicts with remediation action");
    }

    @Test
    void rejects_conflicting_pending_order_command_flag_and_action() {
        assertThatThrownBy(() -> new ExecutionProperties.PendingOrderCommand(
                        false,
                        ExecutionProperties.InterventionAction.MANUAL_REVIEW
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pending order command reject flag conflicts with remediation action");
    }

    @Test
    void rejects_non_positive_order_limit_configuration() {
        assertThatThrownBy(() -> new ExecutionProperties.OrderLimit(
                        true,
                        true,
                        "0",
                        null,
                        true,
                        ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxQuantity must be positive when configured");
    }

    @Test
    void rejects_non_decimal_order_limit_configuration() {
        assertThatThrownBy(() -> new ExecutionProperties.OrderLimit(
                        true,
                        true,
                        null,
                        "not-a-decimal",
                        true,
                        ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxNotional must be a decimal number");
    }

    @Test
    void rejects_non_positive_target_order_limit_configuration() {
        assertThatThrownBy(() -> new ExecutionProperties.OrderLimit.TargetLimit(
                        "binance",
                        "demo",
                        "main",
                        "usd_m_futures",
                        "BTCUSDT",
                        null,
                        "0",
                        true,
                        ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("targetLimits.maxNotional must be positive when configured");
    }
}
