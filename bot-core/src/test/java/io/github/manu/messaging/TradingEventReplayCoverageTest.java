package io.github.manu.messaging;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TradingEventReplayCoverageTest {

    @Test
    void every_trading_event_type_round_trips_through_schema_registry_wire_format() {
        SchemaRegistryTradingEventCodec codec = new SchemaRegistryTradingEventCodec(new InMemorySchemaRegistryClient());
        Map<TradingEventType, TradingEventEnvelope<? extends SpecificRecord>> envelopes =
                TradingEventFixtureFactory.allEnvelopes();

        assertThat(envelopes).containsOnlyKeys(TradingEventType.values());
        envelopes.forEach((eventType, envelope) -> {
            SerializedRegistryTradingEvent serialized = codec.serialize(envelope);
            TradingEventEnvelope<?> decoded =
                    codec.deserialize(eventType, serialized.keyPayload(), serialized.valuePayload());

            assertThat(decoded.eventType()).isEqualTo(eventType);
            assertThat(decoded.route()).isEqualTo(eventType.route());
            assertThat(decoded.value()).isInstanceOf(eventType.recordClass());
            assertThat(decoded.key().getEventType().toString()).isEqualTo(eventType.name());
            assertThat(decoded.key().getPartitionKey().toString())
                    .isEqualTo(envelope.key().getPartitionKey().toString());
        });
    }
}
