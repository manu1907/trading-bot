package io.github.manu.messaging;

import io.github.manu.events.TradingEventType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class KafkaTradingEventRecordConsumer implements TradingEventRecordConsumer {

    private final KafkaConsumer<byte[], byte[]> consumer;
    private final Map<String, TradingEventType> eventTypesByTopic;

    public KafkaTradingEventRecordConsumer(String bootstrapServers, String groupId) {
        this(new KafkaConsumer<>(consumerProperties(bootstrapServers, groupId)));
    }

    KafkaTradingEventRecordConsumer(KafkaConsumer<byte[], byte[]> consumer) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.eventTypesByTopic = Arrays.stream(TradingEventType.values())
                .collect(Collectors.toUnmodifiableMap(type -> type.route().topic(), Function.identity()));
        this.consumer.subscribe(eventTypesByTopic.keySet());
    }

    @Override
    public List<ReceivedTradingEvent> poll(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        ConsumerRecords<byte[], byte[]> records = consumer.poll(timeout);
        return recordsToEvents(records);
    }

    @Override
    public void commitProcessed() {
        consumer.commitSync();
    }

    @Override
    public void close() {
        consumer.close();
    }

    private List<ReceivedTradingEvent> recordsToEvents(ConsumerRecords<byte[], byte[]> records) {
        return records.partitions().stream()
                .flatMap(partition -> records.records(partition).stream())
                .map(this::recordToEvent)
                .toList();
    }

    private ReceivedTradingEvent recordToEvent(ConsumerRecord<byte[], byte[]> record) {
        TradingEventType eventType = eventTypesByTopic.get(record.topic());
        if (eventType == null) {
            throw new MessagingException("No event type registered for topic " + record.topic());
        }
        return new ReceivedTradingEvent(eventType, record.key(), record.value());
    }

    private static Properties consumerProperties(String bootstrapServers, String groupId) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", requireText(bootstrapServers, "bootstrapServers"));
        properties.put("group.id", requireText(groupId, "groupId") + "-" + UUID.randomUUID());
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
