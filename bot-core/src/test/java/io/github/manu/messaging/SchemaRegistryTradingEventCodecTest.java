package io.github.manu.messaging;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventSchemas;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.ConfigChangeEvent;
import io.github.manu.events.v1.ConfigChangeSource;
import io.github.manu.events.v1.TradingEventKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaRegistryTradingEventCodecTest {

    @Test
    void frames_registry_ids_and_decodes_typed_envelopes() {
        SchemaRegistryTradingEventCodec codec =
                new SchemaRegistryTradingEventCodec(new InMemorySchemaRegistryClient());
        TradingEventEnvelope<ConfigChangeEvent> envelope = configChangeEnvelope();

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

    private static TradingEventEnvelope<ConfigChangeEvent> configChangeEnvelope() {
        TradingEventKey key = TradingEventKeys.config(
                TradingEventType.CONFIG_CHANGE,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "/providers/binance/environments/demo/accounts/main/enabled"
        );
        ConfigChangeEvent event = ConfigChangeEvent.newBuilder()
                .setEventId("evt-config")
                .setSchemaVersion(1)
                .setChangeId("cfg-001")
                .setSource(ConfigChangeSource.RUNTIME_FILE)
                .setProfile("live")
                .setProvider("binance")
                .setEnvironment("demo")
                .setAccount("main")
                .setMarket("usdm_futures")
                .setPath("/providers/binance/environments/demo/accounts/main/enabled")
                .setOldValue("false")
                .setNewValue("true")
                .setApplied(true)
                .setRejectedReason(null)
                .setChangedAtMicros(Instant.parse("2026-05-23T11:00:00Z"))
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.CONFIG_CHANGE, key, event);
    }
}
