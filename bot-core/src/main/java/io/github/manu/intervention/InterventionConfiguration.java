package io.github.manu.intervention;

import io.github.manu.events.TradingEventType;
import io.github.manu.messaging.TradingEventHandlerRegistration;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(InterventionProperties.class)
public class InterventionConfiguration {

    @Bean
    @ConditionalOnBean(TradingEventBus.class)
    InterventionAcknowledgementService interventionAcknowledgementService(
            TradingEventBus eventBus,
            TradingStateProjection projection
    ) {
        return new InterventionAcknowledgementService(eventBus, projection);
    }

    @Bean
    @ConditionalOnBean(TradingEventBus.class)
    InterventionRemediationDecisionService interventionRemediationDecisionService(
            TradingEventBus eventBus,
            InterventionRemediationAdvisor remediationAdvisor
    ) {
        return new InterventionRemediationDecisionService(eventBus, remediationAdvisor);
    }

    @Bean
    InterventionRemediationAdvisor interventionRemediationAdvisor(
            TradingStateProjection projection,
            InterventionProperties properties
    ) {
        return new InterventionRemediationAdvisor(projection, properties);
    }

    @Bean
    @ConditionalOnBean(TradingEventBus.class)
    @ConditionalOnProperty(
            prefix = "trading.intervention.remediation-orchestrator",
            name = "enabled",
            havingValue = "true"
    )
    InterventionRemediationOrchestrator interventionRemediationOrchestrator(
            TradingEventBus eventBus,
            TradingStateProjection projection,
            InterventionProperties properties
    ) {
        return new InterventionRemediationOrchestrator(eventBus, projection, properties);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "trading.intervention.remediation-orchestrator",
            name = "enabled",
            havingValue = "true"
    )
    TradingEventHandlerRegistration interventionRemediationOrchestratorHandler(
            InterventionRemediationOrchestrator orchestrator
    ) {
        return TradingEventHandlerRegistration.liveOnly(TradingEventType.REMEDIATION_DECISION, orchestrator);
    }
}
