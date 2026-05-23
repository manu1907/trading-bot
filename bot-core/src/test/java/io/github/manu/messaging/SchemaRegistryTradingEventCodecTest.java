package io.github.manu.messaging;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventSchemas;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.ConfigChangeEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaRegistryTradingEventCodecTest {

    @Test
    void frames_registry_ids_and_decodes_typed_envelopes() {
        SchemaRegistryTradingEventCodec codec =
                new SchemaRegistryTradingEventCodec(new InMemorySchemaRegistryClient());
        TradingEventEnvelope<ConfigChangeEvent> envelope = TradingEventFixtureFactory.configChange();

        SerializedRegistryTradingEvent serialized = codec.serialize(envelope);
        TradingEventEnvelope<?> decoded =
                codec.deserialize(TradingEventType.CONFIG_CHANGE, serialized.keyPayload(), serialized.valuePayload());

        assertThat(serialized.keyPayload()).hasSizeGreaterThan(5);
        assertThat(serialized.valuePayload()).hasSizeGreaterThan(5);
        assertThat(serialized.keyFingerprint())
                .isEqualTo(TradingEventSchemas.fingerprint(TradingEventType.CONFIG_CHANGE.keySchema()));
        assertThat(serialized.valueFingerprint())
                .isEqualTo(TradingEventSchemas.fingerprint(TradingEventType.CONFIG_CHANGE.avroSchema()));
        assertThat(decoded.key().getPartitionKey().toString())
                .isEqualTo(envelope.key().getPartitionKey().toString());
        assertThat(decoded.value())
                .isInstanceOfSatisfying(ConfigChangeEvent.class, value -> {
                    assertThat(value.getChangeId().toString()).isEqualTo("cfg-001");
                    assertThat(value.getApplied()).isTrue();
                });
    }

    @Test
    void rejects_payloads_without_schema_registry_frame() {
        SchemaRegistryTradingEventCodec codec =
                new SchemaRegistryTradingEventCodec(new InMemorySchemaRegistryClient());

        assertThatThrownBy(() -> codec.deserialize(
                TradingEventType.CONFIG_CHANGE,
                new byte[] { 1, 2, 3 },
                new byte[] { 1, 2, 3 }
        ))
                .isInstanceOf(SchemaRegistryException.class)
                .hasMessageContaining("Invalid schema-registry Avro payload");
    }
}
