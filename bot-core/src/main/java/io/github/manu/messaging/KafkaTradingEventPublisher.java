package io.github.manu.messaging;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public final class KafkaTradingEventPublisher implements TradingEventPublisher, AutoCloseable {

    private final KafkaProducer<byte[], byte[]> producer;
    private final SchemaRegistryTradingEventCodec codec;

    public KafkaTradingEventPublisher(
            String bootstrapServers,
            String clientId,
            SchemaRegistryTradingEventCodec codec
    ) {
        this(new KafkaProducer<>(producerProperties(bootstrapServers, clientId)), codec);
    }

    KafkaTradingEventPublisher(
            KafkaProducer<byte[], byte[]> producer,
            SchemaRegistryTradingEventCodec codec
    ) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public Future<RecordMetadata> publish(TradingEventEnvelope<? extends SpecificRecord> envelope) {
        Objects.requireNonNull(envelope, "envelope");
        SerializedRegistryTradingEvent serialized = codec.serialize(envelope);
        TradingEventType eventType = serialized.eventType();
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(
                serialized.route().topic(),
                serialized.keyPayload(),
                serialized.valuePayload()
        );
        record.headers().add(header(TradingEventHeaders.EVENT_TYPE, eventType.name()));
        record.headers().add(header(TradingEventHeaders.VALUE_SCHEMA_FINGERPRINT, serialized.valueFingerprint()));
        return producer.send(record);
    }

    @Override
    public CompletableFuture<PublishedTradingEvent> publishAsync(
            TradingEventEnvelope<? extends SpecificRecord> envelope
    ) {
        Objects.requireNonNull(envelope, "envelope");
        SerializedRegistryTradingEvent serialized = codec.serialize(envelope);
        TradingEventType eventType = serialized.eventType();
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(
                serialized.route().topic(),
                serialized.keyPayload(),
                serialized.valuePayload()
        );
        record.headers().add(header(TradingEventHeaders.EVENT_TYPE, eventType.name()));
        record.headers().add(header(TradingEventHeaders.VALUE_SCHEMA_FINGERPRINT, serialized.valueFingerprint()));

        CompletableFuture<PublishedTradingEvent> result = new CompletableFuture<>();
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                result.completeExceptionally(new MessagingException("Failed to publish trading event", exception));
            } else {
                result.complete(new PublishedTradingEvent(
                        eventType,
                        metadata.topic(),
                        metadata.partition(),
                        metadata.offset()
                ));
            }
        });
        return result;
    }

    @Override
    public void close() {
        producer.close();
    }

    private static Properties producerProperties(String bootstrapServers, String clientId) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", requireText(bootstrapServers, "bootstrapServers"));
        properties.put("client.id", requireText(clientId, "clientId"));
        properties.put("key.serializer", ByteArraySerializer.class.getName());
        properties.put("value.serializer", ByteArraySerializer.class.getName());
        properties.put("acks", "all");
        properties.put("enable.idempotence", "true");
        return properties;
    }

    private static RecordHeader header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static RecordHeader header(String name, long value) {
        return header(name, Long.toString(value));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
