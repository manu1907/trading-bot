package io.github.manu.events;

import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TradingEventSchemaManifestTest {

    @Test
    void manifest_contains_one_descriptor_per_event_type() {
        assertThat(TradingEventSchemaManifest.descriptors())
                .hasSize(TradingEventType.values().length)
                .extracting(TradingEventSchemaDescriptor::eventType)
                .containsExactly(TradingEventType.values());
    }

    @Test
    void manifest_exposes_unique_topics_and_value_fingerprints() {
        assertThat(TradingEventSchemaManifest.descriptors().stream()
                .map(descriptor -> descriptor.route().topic())
                .collect(Collectors.toSet()))
                .hasSize(TradingEventType.values().length);
        assertThat(TradingEventSchemaManifest.descriptors().stream()
                .map(TradingEventSchemaDescriptor::valueSchemaFingerprint)
                .collect(Collectors.toSet()))
                .hasSize(TradingEventType.values().length);
        assertThat(TradingEventSchemaManifest.descriptors())
                .allSatisfy(descriptor -> {
                    assertThat(descriptor.keySchemaFullName())
                            .isEqualTo(TradingEventSchemas.NAMESPACE + ".TradingEventKey");
                    assertThat(descriptor.keySchemaFingerprint()).isNotZero();
                    assertThat(descriptor.valueSchemaFullName()).startsWith(TradingEventSchemas.NAMESPACE + ".");
                    assertThat(descriptor.valueSchemaFingerprint()).isNotZero();
                });
    }

    @Test
    void manifest_schemas_are_backward_and_forward_compatible_with_their_current_versions() {
        for (TradingEventSchemaDescriptor descriptor : TradingEventSchemaManifest.descriptors()) {
            assertCompatible(descriptor.eventType().keySchema(), descriptor.eventType().keySchema());
            assertCompatible(descriptor.eventType().avroSchema(), descriptor.eventType().avroSchema());
        }
    }

    private void assertCompatible(Schema reader, Schema writer) {
        SchemaCompatibility.SchemaCompatibilityResult result =
                SchemaCompatibility.checkReaderWriterCompatibility(reader, writer).getResult();
        assertThat(result.getCompatibility())
                .as(reader.getFullName() + " reads " + writer.getFullName())
                .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
    }
}
