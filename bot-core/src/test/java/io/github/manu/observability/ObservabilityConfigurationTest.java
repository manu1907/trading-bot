package io.github.manu.observability;

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
            .withBean(PauseGovernanceMetrics.class, () -> new PauseGovernanceMetrics(new SimpleMeterRegistry()))
            .withBean(PauseGovernanceDecisionMetricsHandler.class);

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
}
