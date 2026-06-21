package io.github.manu.position;

import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PositionProperties.class)
public class PositionConfiguration {

    @Bean
    PositionManager positionManager(
            TradingStateProjection projection,
            PositionProperties properties,
            ObjectProvider<TradingEventBus> eventBus,
            ObjectProvider<ReconciliationConfidenceTracker> reconciliationConfidenceTracker
    ) {
        return new PositionManager(
                projection,
                properties,
                eventBus.getIfAvailable(),
                reconciliationConfidenceTracker.getIfAvailable()
        );
    }
}
