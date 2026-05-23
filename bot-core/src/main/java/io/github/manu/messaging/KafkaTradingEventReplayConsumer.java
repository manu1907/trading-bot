package io.github.manu.messaging;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

public final class KafkaTradingEventReplayConsumer implements AutoCloseable {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private final KafkaConsumer<byte[], byte[]> consumer;
    private final SchemaRegistryTradingEventCodec codec;

    public KafkaTradingEventReplayConsumer(
            String bootstrapServers,
            String groupIdPrefix,
            SchemaRegistryTradingEventCodec codec
    ) {
        this(new KafkaConsumer<>(consumerProperties(bootstrapServers, groupIdPrefix)), codec);
    }

    KafkaTradingEventReplayConsumer(
            KafkaConsumer<byte[], byte[]> consumer,
            SchemaRegistryTradingEventCodec codec
    ) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public List<TradingEventEnvelope<?>> replay(
            TradingEventType eventType,
            int expectedRecords,
            Duration timeout
    ) {
        Objects.requireNonNull(eventType, "eventType");
        if (expectedRecords <= 0) {
            throw new IllegalArgumentException("expectedRecords must be positive");
        }
        Objects.requireNonNull(timeout, "timeout");

        consumer.subscribe(List.of(eventType.route().topic()));
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        List<TradingEventEnvelope<?>> events = new ArrayList<>();
        while (events.size() < expectedRecords && System.nanoTime() < deadlineNanos) {
            ConsumerRecords<byte[], byte[]> records = consumer.poll(POLL_INTERVAL);
            for (ConsumerRecord<byte[], byte[]> record : records) {
                events.add(codec.deserialize(eventType, record.key(), record.value()));
                if (events.size() == expectedRecords) {
                    break;
                }
            }
        }
        return events;
    }

    @Override
    public void close() {
        consumer.close();
    }

    private static Properties consumerProperties(String bootstrapServers, String groupIdPrefix) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", requireText(bootstrapServers, "bootstrapServers"));
        properties.put("group.id", requireText(groupIdPrefix, "groupIdPrefix") + "-" + UUID.randomUUID());
        properties.put("key.deserializer", ByteArrayDeserializer.class.getName());
        properties.put("value.deserializer", ByteArrayDeserializer.class.getName());
        properties.put("auto.offset.reset", "earliest");
        properties.put("enable.auto.commit", "false");
        return properties;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
