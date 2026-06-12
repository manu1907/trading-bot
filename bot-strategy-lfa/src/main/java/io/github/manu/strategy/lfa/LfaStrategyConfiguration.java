package io.github.manu.strategy.lfa;

import io.github.manu.execution.ExecutionProperties;
import io.github.manu.execution.StrategyInstrumentUniverseResolver;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(LfaStrategyProperties.class)
public class LfaStrategyConfiguration {

    @Bean
    @ConditionalOnBean({TradingEventBus.class, TradingStateProjection.class})
    @ConditionalOnProperty(prefix = "trading.strategy.lfa.signal-runner", name = "enabled", havingValue = "true")
    LfaSignalRunner lfaSignalRunner(
            LfaMarketSignalAnalyzer analyzer,
            LfaStrategyProperties properties,
            TradingStateProjection projection,
            TradingEventBus eventBus,
            ExecutionProperties executionProperties,
            ObjectProvider<StrategyInstrumentUniverseResolver> instrumentUniverseResolver
    ) {
        return new LfaSignalRunner(
                analyzer,
                properties,
                projection,
                eventBus,
                executionProperties,
                instrumentUniverseResolver.getIfAvailable()
        );
    }
}
