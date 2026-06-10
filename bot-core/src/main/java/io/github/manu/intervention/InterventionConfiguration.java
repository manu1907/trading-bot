package io.github.manu.intervention;

import io.github.manu.audit.AuditLogger;
import io.github.manu.config.runtime.ConfigManager;
import io.github.manu.events.TradingEventType;
import io.github.manu.execution.OrderExecutionPipeline;
import io.github.manu.messaging.TradingEventHandlerRegistration;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.observability.PauseGovernanceMetrics;
import io.github.manu.projection.TradingStateProjection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
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
    @ConditionalOnBean(TradingEventBus.class)
    PauseGovernanceControlService pauseGovernanceControlService(
            TradingEventBus eventBus,
            TradingStateProjection projection,
            AuditLogger auditLogger,
            PauseGovernanceMetrics pauseGovernanceMetrics
    ) {
        return new PauseGovernanceControlService(eventBus, projection, auditLogger, pauseGovernanceMetrics);
    }

    @Bean
    InterventionRemediationAdvisor interventionRemediationAdvisor(
            TradingStateProjection projection,
            InterventionProperties properties
    ) {
        return new InterventionRemediationAdvisor(projection, properties);
    }

    @Bean
    InterventionRemediationCommandPlanner interventionRemediationCommandPlanner(
            TradingStateProjection projection,
            InterventionProperties properties
    ) {
        return new InterventionRemediationCommandPlanner(
                projection,
                properties.remediationExecutorPolicy().positionOrderPolicy(),
                properties.remediationExecutorPolicy().managedOrderAmendmentPolicy(),
                properties.remediationExecutorPolicy().adoptedOrderLifecyclePolicy()
        );
    }

    @Bean
    InterventionRemediationExecutorService interventionRemediationExecutorService(
            TradingStateProjection projection,
            InterventionRemediationCommandPlanner commandPlanner,
            InterventionProperties properties,
            ObjectProvider<OrderExecutionPipeline> orderExecutionPipeline
    ) {
        return new InterventionRemediationExecutorService(
                projection,
                commandPlanner,
                properties,
                orderExecutionPipeline.getIfAvailable()
        );
    }

    @Bean
    @ConditionalOnBean(TradingEventBus.class)
    InterventionAutomatedDecisionService interventionAutomatedDecisionService(
            TradingEventBus eventBus,
            InterventionRemediationAdvisor remediationAdvisor,
            TradingStateProjection projection,
            InterventionProperties properties
    ) {
        return new InterventionAutomatedDecisionService(eventBus, remediationAdvisor, projection, properties);
    }

    @Bean
    @ConditionalOnBean(InterventionAutomatedDecisionService.class)
    @ConditionalOnProperty(
            prefix = "trading.intervention.automated-remediation-runner",
            name = "enabled",
            havingValue = "true"
    )
    InterventionAutomatedRemediationRunner interventionAutomatedRemediationRunner(
            InterventionAutomatedDecisionService automatedDecisionService,
            InterventionRemediationExecutorService remediationExecutorService,
            InterventionProperties properties,
            ConfigManager configManager
    ) {
        return new InterventionAutomatedRemediationRunner(
                automatedDecisionService,
                remediationExecutorService,
                properties,
                configManager
        );
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
