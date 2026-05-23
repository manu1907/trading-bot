package io.github.manu.journal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JournalProperties.class)
public class JournalConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "trading.journal", name = "enabled", havingValue = "true")
    TradingEventJournal tradingEventJournal(JournalProperties properties) {
        return new ChronicleTradingEventJournal(properties.directory());
    }
}
