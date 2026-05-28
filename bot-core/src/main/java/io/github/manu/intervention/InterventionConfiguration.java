package io.github.manu.intervention;

import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
}
