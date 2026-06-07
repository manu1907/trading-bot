package io.github.manu.execution;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ExecutionConfiguration.class);

    @Test
    void binds_manual_intervention_remediation_actions_from_properties() {
        contextRunner
                .withPropertyValues(
                        "trading.execution.risk-gate.manual-intervention.external-order-action=reject-new-commands",
                        "trading.execution.risk-gate.manual-intervention.external-order-apply-to-target-commands=true",
                        "trading.execution.risk-gate.manual-intervention.external-position-action=manual-review",
                        "trading.execution.risk-gate.manual-intervention.external-position-apply-to-target-commands=true",
                        "trading.execution.risk-gate.unknown-order-status.action=reject-new-commands",
                        "trading.execution.risk-gate.unknown-order-status.apply-to-target-commands=true",
                        "trading.execution.risk-gate.pending-order-command.action=allow-new-commands",
                        "trading.execution.risk-gate.pending-order-command.apply-to-target-commands=true",
                        "trading.execution.risk-gate.pause-governance.override-enabled=true",
                        "trading.execution.risk-gate.pause-governance.require-override-actor=true",
                        "trading.execution.risk-gate.pause-governance.require-override-reason=true",
                        "trading.execution.risk-gate.pause-governance.require-override-expiry=true",
                        "trading.execution.risk-gate.pause-governance.max-override-seconds=300",
                        "trading.execution.idempotency.reject-projected-duplicates=false"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ExecutionProperties.class);
                    ExecutionProperties properties = context.getBean(ExecutionProperties.class);
                    ExecutionProperties.ManualIntervention manualIntervention =
                            properties.riskGate().manualIntervention();
                    assertThat(manualIntervention.externalOrderAction())
                            .isEqualTo(ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS);
                    assertThat(manualIntervention.externalOrderApplyToTargetCommands()).isTrue();
                    assertThat(manualIntervention.externalPositionAction())
                            .isEqualTo(ExecutionProperties.InterventionAction.MANUAL_REVIEW);
                    assertThat(manualIntervention.externalPositionApplyToTargetCommands()).isTrue();
                    assertThat(properties.riskGate().unknownOrderStatus().action())
                            .isEqualTo(ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS);
                    assertThat(properties.riskGate().unknownOrderStatus().applyToTargetCommands()).isTrue();
                    assertThat(properties.riskGate().pendingOrderCommand().action())
                            .isEqualTo(ExecutionProperties.InterventionAction.ALLOW_NEW_COMMANDS);
                    assertThat(properties.riskGate().pendingOrderCommand().applyToTargetCommands()).isTrue();
                    assertThat(properties.riskGate().pauseGovernance().overrideEnabled()).isTrue();
                    assertThat(properties.riskGate().pauseGovernance().requireOverrideActor()).isTrue();
                    assertThat(properties.riskGate().pauseGovernance().requireOverrideReason()).isTrue();
                    assertThat(properties.riskGate().pauseGovernance().requireOverrideExpiry()).isTrue();
                    assertThat(properties.riskGate().pauseGovernance().maxOverrideSeconds()).isEqualTo(300);
                    assertThat(properties.idempotency().rejectProjectedDuplicates()).isFalse();
                });
    }
}
