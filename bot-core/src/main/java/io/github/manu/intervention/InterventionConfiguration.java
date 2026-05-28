package io.github.manu.intervention;

import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
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
