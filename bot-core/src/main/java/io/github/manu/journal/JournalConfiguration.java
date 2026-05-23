package io.github.manu.journal;

import io.github.manu.messaging.TradingEventHandlerRegistry;
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

    @Bean
    @ConditionalOnProperty(prefix = "trading.journal.recovery", name = "enabled", havingValue = "true")
    JournalRecoveryService journalRecoveryService(
            TradingEventJournal journal,
            TradingEventHandlerRegistry handlerRegistry
    ) {
        return new JournalRecoveryService(journal, handlerRegistry);
    }
}
