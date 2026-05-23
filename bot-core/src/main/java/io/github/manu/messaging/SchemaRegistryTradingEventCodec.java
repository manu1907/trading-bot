package io.github.manu.messaging;

import io.github.manu.events.SerializedTradingEvent;
import io.github.manu.events.TradingEventCodec;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventMessageCodec;
import io.github.manu.events.TradingEventSchemas;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.TradingEventKey;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class SchemaRegistryTradingEventCodec {

    private static final byte MAGIC_BYTE = 0;
    private static final int HEADER_BYTES = 5;

    private final SchemaRegistryClient registryClient;
    private final TradingEventMessageCodec messageCodec;
    private final Map<String, Integer> registeredIds = new ConcurrentHashMap<>();

    public SchemaRegistryTradingEventCodec(SchemaRegistryClient registryClient) {
        this(registryClient, new TradingEventMessageCodec());
    }

    SchemaRegistryTradingEventCodec(
            SchemaRegistryClient registryClient,
            TradingEventMessageCodec messageCodec
    ) {
        this.registryClient = Objects.requireNonNull(registryClient, "registryClient");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec");
    }

    public SerializedRegistryTradingEvent serialize(TradingEventEnvelope<? extends SpecificRecord> envelope) {
        Objects.requireNonNull(envelope, "envelope");
        SerializedTradingEvent serialized = messageCodec.serialize(envelope);
        int keySchemaId = schemaId(serialized.route().keySubject(), envelope.eventType().keySchema());
        int valueSchemaId = schemaId(serialized.route().valueSubject(), envelope.eventType().avroSchema());
        return new SerializedRegistryTradingEvent(
                serialized.eventType(),
                serialized.route(),
                frame(keySchemaId, serialized.keyPayload()),
                frame(valueSchemaId, serialized.valuePayload()),
                TradingEventSchemas.fingerprint(envelope.eventType().keySchema()),
                TradingEventSchemas.fingerprint(envelope.eventType().avroSchema())
        );
    }

    public TradingEventEnvelope<?> deserialize(
            TradingEventType eventType,
            byte[] keyPayload,
            byte[] valuePayload
    ) {
        Objects.requireNonNull(eventType, "eventType");
        FramedPayload framedKey = unframe(keyPayload);
        FramedPayload framedValue = unframe(valuePayload);
        verifySchema(eventType.route().keySubject(), eventType.keySchema(), framedKey.schemaId());
        verifySchema(eventType.route().valueSubject(), eventType.avroSchema(), framedValue.schemaId());

        TradingEventKey key = TradingEventCodec.<TradingEventKey>of(eventType.keySchema()).decode(framedKey.payload());
        SpecificRecord value = TradingEventCodec.<SpecificRecord>of(eventType.avroSchema())
                .decode(framedValue.payload());
        return TradingEventEnvelope.of(eventType, key, value);
    }

    private int schemaId(String subject, Schema schema) {
        return registeredIds.computeIfAbsent(subject, ignored -> registryClient.register(subject, schema));
    }

    private void verifySchema(String subject, Schema expected, int schemaId) {
        Schema actual = registryClient.schemaById(schemaId);
        long expectedFingerprint = TradingEventSchemas.fingerprint(expected);
        long actualFingerprint = TradingEventSchemas.fingerprint(actual);
        if (actualFingerprint != expectedFingerprint) {
            throw new SchemaRegistryException(
                    "Schema id " + schemaId + " for subject " + subject + " does not match expected fingerprint"
            );
        }
    }

    private static byte[] frame(int schemaId, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (schemaId <= 0) {
            throw new IllegalArgumentException("schemaId must be positive");
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + payload.length);
        buffer.put(MAGIC_BYTE);
        buffer.putInt(schemaId);
        buffer.put(payload);
        return buffer.array();
    }

    private static FramedPayload unframe(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length <= HEADER_BYTES || payload[0] != MAGIC_BYTE) {
            throw new SchemaRegistryException("Invalid schema-registry Avro payload");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.get();
        int schemaId = buffer.getInt();
        byte[] avroPayload = new byte[buffer.remaining()];
        buffer.get(avroPayload);
        return new FramedPayload(schemaId, avroPayload);
    }

    private record FramedPayload(int schemaId, byte[] payload) {
    }
}
