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
                        "trading.execution.risk-gate.manual-intervention.external-position-action=manual-review",
                        "trading.execution.risk-gate.unknown-order-status.action=reject-new-commands",
                        "trading.execution.idempotency.reject-projected-duplicates=false"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ExecutionProperties.class);
                    ExecutionProperties properties = context.getBean(ExecutionProperties.class);
                    ExecutionProperties.ManualIntervention manualIntervention =
                            properties.riskGate().manualIntervention();
                    assertThat(manualIntervention.externalOrderAction())
                            .isEqualTo(ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS);
                    assertThat(manualIntervention.externalPositionAction())
                            .isEqualTo(ExecutionProperties.InterventionAction.MANUAL_REVIEW);
                    assertThat(properties.riskGate().unknownOrderStatus().action())
                            .isEqualTo(ExecutionProperties.InterventionAction.REJECT_NEW_COMMANDS);
                    assertThat(properties.idempotency().rejectProjectedDuplicates()).isFalse();
                });
    }
}
