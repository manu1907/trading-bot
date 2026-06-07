package io.github.manu.observability;

import io.github.manu.audit.AuditLogger;
import io.github.manu.audit.PauseGovernanceAuditTrail;
import io.github.manu.events.TradingEventType;
import io.github.manu.messaging.TradingEventHandlerRegistration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ObservabilityConfiguration.class)
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(PauseGovernanceAuditTrail.class, PauseGovernanceAuditTrail::new)
            .withBean(AuditLogger.class)
            .withBean(PauseGovernanceMetrics.class, () -> new PauseGovernanceMetrics(new SimpleMeterRegistry()))
            .withBean(PauseGovernanceDecisionMetricsHandler.class)
            .withBean(PauseGovernanceDecisionAuditHandler.class);

    @Test
    void registers_pause_governance_decision_metrics_as_live_only() {
        contextRunner.run(context -> {
            TradingEventHandlerRegistration registration =
                    context.getBean("pauseGovernanceDecisionMetricsHandlerRegistration", TradingEventHandlerRegistration.class);

            assertThat(registration.eventType()).isEqualTo(TradingEventType.REMEDIATION_DECISION);
            assertThat(registration.live()).isTrue();
            assertThat(registration.replay()).isFalse();
        });
    }

    @Test
    void registers_pause_governance_decision_audit_as_live_only() {
        contextRunner.run(context -> {
            TradingEventHandlerRegistration registration =
                    context.getBean("pauseGovernanceDecisionAuditHandlerRegistration", TradingEventHandlerRegistration.class);

            assertThat(registration.eventType()).isEqualTo(TradingEventType.REMEDIATION_DECISION);
            assertThat(registration.live()).isTrue();
            assertThat(registration.replay()).isFalse();
        });
    }
}
