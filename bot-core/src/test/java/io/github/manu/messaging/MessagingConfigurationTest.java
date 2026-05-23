package io.github.manu.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MessagingConfiguration.class);

    @Test
    void keeps_kafka_clients_disabled_by_default() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(MessagingProperties.class)
                .doesNotHaveBean(SchemaRegistryClient.class)
                .doesNotHaveBean(KafkaTradingEventPublisher.class)
                .doesNotHaveBean(KafkaDeadLetterPublisher.class)
                .doesNotHaveBean(TradingEventBus.class)
                .doesNotHaveBean(TradingEventReplayConsumerFactory.class)
                .doesNotHaveBean(ApplicationRunner.class));
    }

    @Test
    void creates_messaging_beans_when_enabled() {
        contextRunner
                .withPropertyValues(
                        "trading.messaging.enabled=true",
                        "trading.messaging.bootstrap-servers=localhost:19092",
                        "trading.messaging.schema-registry-url=http://localhost:18081",
                        "trading.messaging.client-id-prefix=trading-bot-test"
                )
                .run(context -> assertThat(context)
                        .hasSingleBean(MessagingProperties.class)
                        .hasSingleBean(SchemaRegistryClient.class)
                        .hasSingleBean(SchemaRegistryTradingEventCodec.class)
                        .hasSingleBean(KafkaTradingEventPublisher.class)
                        .hasSingleBean(KafkaDeadLetterPublisher.class)
                        .hasSingleBean(TradingEventBus.class)
                        .hasSingleBean(TradingEventReplayConsumerFactory.class)
                        .doesNotHaveBean(ApplicationRunner.class));
    }

    @Test
    void exposes_topic_provisioner_only_when_auto_create_is_enabled() {
        contextRunner
                .withPropertyValues(
                        "trading.messaging.enabled=true",
                        "trading.messaging.topics.auto-create=true",
                        "trading.messaging.topics.replication-factor=1"
                )
                .run(context -> assertThat(context).hasSingleBean(ApplicationRunner.class));
    }

    @Test
    void binds_explicit_messaging_properties() {
        contextRunner
                .withPropertyValues(
                        "trading.messaging.enabled=true",
                        "trading.messaging.bootstrap-servers=localhost:19092",
                        "trading.messaging.schema-registry-url=http://localhost:18081",
                        "trading.messaging.client-id-prefix=bot-a",
                        "trading.messaging.topics.auto-create=true",
                        "trading.messaging.topics.replication-factor=3"
                )
                .run(context -> {
                    MessagingProperties properties = context.getBean(MessagingProperties.class);

                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.bootstrapServers()).isEqualTo("localhost:19092");
                    assertThat(properties.schemaRegistryUrl()).isEqualTo("http://localhost:18081");
                    assertThat(properties.clientIdPrefix()).isEqualTo("bot-a");
                    assertThat(properties.topics().autoCreate()).isTrue();
                    assertThat(properties.topics().replicationFactor()).isEqualTo((short) 3);
                });
    }
}
