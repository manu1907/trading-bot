package io.github.manu.messaging;

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

public final class KafkaDeadLetterPublisher implements AutoCloseable {

    private final KafkaProducer<byte[], byte[]> producer;

    public KafkaDeadLetterPublisher(String bootstrapServers, String clientId) {
        this(new KafkaProducer<>(producerProperties(bootstrapServers, clientId)));
    }

    KafkaDeadLetterPublisher(KafkaProducer<byte[], byte[]> producer) {
        this.producer = Objects.requireNonNull(producer, "producer");
    }

    public Future<RecordMetadata> publish(DeadLetterTradingEvent event) {
        Objects.requireNonNull(event, "event");
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(
                event.route().deadLetterTopic(),
                event.keyPayload(),
                event.valuePayload()
        );
        record.headers().add(header(TradingEventHeaders.EVENT_TYPE, event.eventType().name()));
        record.headers().add(header(TradingEventHeaders.DEAD_LETTER_REASON, event.reason()));
        return producer.send(record);
    }

    public CompletableFuture<RecordMetadata> publishAsync(DeadLetterTradingEvent event) {
        Future<RecordMetadata> send = publish(event);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return send.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new MessagingException("Interrupted while publishing dead-letter event", ex);
            } catch (Exception ex) {
                throw new MessagingException("Failed to publish dead-letter event", ex);
            }
        });
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

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
