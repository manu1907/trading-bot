package io.github.manu.observability;

import io.github.manu.events.TradingEventType;
import io.github.manu.messaging.TradingEventHandlerRegistration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfiguration {

    @Bean
    TradingEventHandlerRegistration pauseGovernanceDecisionMetricsHandlerRegistration(
            PauseGovernanceDecisionMetricsHandler handler
    ) {
        return TradingEventHandlerRegistration.liveOnly(TradingEventType.REMEDIATION_DECISION, handler);
    }
}
