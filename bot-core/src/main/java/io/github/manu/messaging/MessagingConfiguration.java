package io.github.manu.messaging;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "trading.messaging", name = "enabled", havingValue = "true")
    SchemaRegistryClient schemaRegistryClient(MessagingProperties properties) {
        return new RedpandaSchemaRegistryClient(properties.schemaRegistryUrl());
    }

    @Bean
    @ConditionalOnProperty(prefix = "trading.messaging", name = "enabled", havingValue = "true")
    SchemaRegistryTradingEventCodec schemaRegistryTradingEventCodec(SchemaRegistryClient schemaRegistryClient) {
        return new SchemaRegistryTradingEventCodec(schemaRegistryClient);
    }

    @Bean
    @ConditionalOnProperty(prefix = "trading.messaging", name = "enabled", havingValue = "true")
    KafkaTradingEventPublisher kafkaTradingEventPublisher(
            MessagingProperties properties,
            SchemaRegistryTradingEventCodec codec
    ) {
        return new KafkaTradingEventPublisher(
                properties.bootstrapServers(),
                properties.clientIdPrefix() + "-producer",
                codec
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "trading.messaging", name = "enabled", havingValue = "true")
    KafkaDeadLetterPublisher kafkaDeadLetterPublisher(MessagingProperties properties) {
        return new KafkaDeadLetterPublisher(
                properties.bootstrapServers(),
                properties.clientIdPrefix() + "-dead-letter"
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "trading.messaging", name = "enabled", havingValue = "true")
    TradingEventBus tradingEventBus(
            KafkaTradingEventPublisher publisher,
            KafkaDeadLetterPublisher deadLetterPublisher
    ) {
        return new RedpandaTradingEventBus(publisher, deadLetterPublisher);
    }

    @Bean
    @ConditionalOnProperty(prefix = "trading.messaging", name = "enabled", havingValue = "true")
    TradingEventDispatcher tradingEventDispatcher(
            SchemaRegistryTradingEventCodec codec,
            KafkaDeadLetterPublisher deadLetterPublisher
    ) {
        return new TradingEventDispatcher(codec, deadLetterPublisher, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix = "trading.messaging", name = "enabled", havingValue = "true")
    TradingEventReplayConsumerFactory tradingEventReplayConsumerFactory(
            MessagingProperties properties,
            SchemaRegistryTradingEventCodec codec
    ) {
        return new TradingEventReplayConsumerFactory(properties.bootstrapServers(), properties.clientIdPrefix(), codec);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "trading.messaging.topics",
            name = "auto-create",
            havingValue = "true"
    )
    ApplicationRunner tradingEventTopicProvisioner(MessagingProperties properties) {
        return ignored -> {
            try (TradingEventTopicAdmin admin =
                    new TradingEventTopicAdmin(properties.bootstrapServers(), properties.topics().replicationFactor())) {
                admin.createMissingTopics(TradingEventTopicCatalog.allTopics());
            }
        };
    }
}
