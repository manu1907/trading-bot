package io.github.manu.messaging;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderCommandSide;
import io.github.manu.events.v1.OrderCommandType;
import io.github.manu.events.v1.TradingEventKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.redpanda.RedpandaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "redpanda.integration.tests", matches = "true")
class KafkaTradingEventIntegrationTest {

    @Test
    void publishes_and_replays_registry_encoded_trading_events() throws Exception {
        try (RedpandaContainer redpanda = redpanda()) {
            redpanda.start();
            SchemaRegistryTradingEventCodec codec = new SchemaRegistryTradingEventCodec(
                    new RedpandaSchemaRegistryClient(redpanda.getSchemaRegistryAddress())
            );
            try (TradingEventTopicAdmin admin = new TradingEventTopicAdmin(redpanda.getBootstrapServers(), (short) 1);
                KafkaTradingEventPublisher publisher = new KafkaTradingEventPublisher(
                        redpanda.getBootstrapServers(),
                        "trading-bot-test-producer",
                        codec
                );
                KafkaTradingEventReplayConsumer consumer = new KafkaTradingEventReplayConsumer(
                        redpanda.getBootstrapServers(),
                        "trading-bot-test-replay",
                        codec
                )) {
                admin.createMissingTopics(TradingEventTopicCatalog.allTopics());

                TradingEventEnvelope<OrderCommandEvent> command = orderCommandEnvelope();
                publisher.publish(command).get();

                List<TradingEventEnvelope<?>> replayed =
                        consumer.replay(TradingEventType.ORDER_COMMAND, 1, Duration.ofSeconds(10));

                assertThat(replayed).hasSize(1);
                assertThat(replayed.getFirst().key().getPartitionKey().toString())
                        .isEqualTo(command.key().getPartitionKey().toString());
                assertThat(replayed.getFirst().value())
                        .isInstanceOfSatisfying(OrderCommandEvent.class, value -> {
                            assertThat(value.getCommandId().toString()).isEqualTo("cmd-001");
                            assertThat(value.getSymbol().toString()).isEqualTo("BTCUSDT");
                        });
            }
        }
    }

    @Test
    void routes_bad_payloads_to_dead_letter_topic() throws Exception {
        try (RedpandaContainer redpanda = redpanda()) {
            redpanda.start();
            SchemaRegistryTradingEventCodec codec = new SchemaRegistryTradingEventCodec(
                    new RedpandaSchemaRegistryClient(redpanda.getSchemaRegistryAddress())
            );
            TradingEventEnvelope<OrderCommandEvent> command = orderCommandEnvelope();
            SerializedRegistryTradingEvent serialized = codec.serialize(command);
            DeadLetterTradingEvent deadLetter = new DeadLetterTradingEvent(
                    TradingEventType.ORDER_COMMAND,
                    TradingEventType.ORDER_COMMAND.route(),
                    serialized.keyPayload(),
                    new byte[] { 1, 2, 3 },
                    "invalid value payload",
                    Instant.parse("2026-05-23T12:15:00Z")
            );

            try (TradingEventTopicAdmin admin = new TradingEventTopicAdmin(redpanda.getBootstrapServers(), (short) 1);
                KafkaDeadLetterPublisher publisher = new KafkaDeadLetterPublisher(
                        redpanda.getBootstrapServers(),
                        "trading-bot-test-dlq"
                )) {
                admin.createMissingTopics(TradingEventTopicCatalog.allTopics());

                assertThat(publisher.publish(deadLetter).get().topic())
                        .isEqualTo(TradingEventType.ORDER_COMMAND.route().deadLetterTopic());
            }
        }
    }

    private static RedpandaContainer redpanda() {
        return new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.1.9");
    }

    private static TradingEventEnvelope<OrderCommandEvent> orderCommandEnvelope() {
        TradingEventKey key = TradingEventKeys.order(
                TradingEventType.ORDER_COMMAND,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "BTCUSDT",
                "tb-lfa-001"
        );
        OrderCommandEvent command = OrderCommandEvent.newBuilder()
                .setEventId("evt-command")
                .setSchemaVersion(1)
                .setCommandId("cmd-001")
                .setStrategyId("lfa")
                .setProvider("binance")
                .setEnvironment("demo")
                .setAccount("main")
                .setMarket("usdm_futures")
                .setSymbol("BTCUSDT")
                .setSide(OrderCommandSide.BUY)
                .setOrderType(OrderCommandType.LIMIT)
                .setQuantity("0.001")
                .setPrice("50000.10")
                .setReduceOnly(false)
                .setClosePosition(false)
                .setClientOrderId("tb-lfa-001")
                .setIdempotencyKey("idem-001")
                .setRequestedAtMicros(Instant.parse("2026-05-23T10:30:00Z"))
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.ORDER_COMMAND, key, command);
    }
}
