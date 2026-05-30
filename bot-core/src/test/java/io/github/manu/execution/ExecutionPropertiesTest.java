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
        assertThat(manualIntervention.externalPositionAction())
                .isEqualTo(ExecutionProperties.InterventionAction.MANUAL_REVIEW);
        assertThat(properties.riskGate().unknownOrderStatus().rejectUnknownOrderStatus()).isTrue();
        assertThat(properties.riskGate().unknownOrderStatus().action())
                .isEqualTo(ExecutionProperties.InterventionAction.MANUAL_REVIEW);
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
}
