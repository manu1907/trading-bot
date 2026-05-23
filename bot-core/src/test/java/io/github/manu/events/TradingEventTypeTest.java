package io.github.manu.events;

import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TradingEventTypeTest {

    @Test
    void every_schema_has_exactly_one_event_type() {
        Set<TradingEventSchema> schemas = Arrays.stream(TradingEventType.values())
                .map(TradingEventType::schema)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(TradingEventSchema.class)));

        assertThat(schemas).containsExactlyInAnyOrder(TradingEventSchema.values());
    }

    @Test
    void topics_and_subjects_follow_the_redpanda_schema_registry_convention() {
        assertThat(Arrays.stream(TradingEventType.values()).map(type -> type.route().topic()))
                .doesNotHaveDuplicates()
                .allSatisfy(topic -> assertThat(topic).startsWith("trading.v1."));

        assertThat(Arrays.stream(TradingEventType.values()).map(type -> type.route().keySubject()))
                .doesNotHaveDuplicates()
                .allSatisfy(subject -> assertThat(subject).endsWith("-key"));
        assertThat(Arrays.stream(TradingEventType.values()).map(type -> type.route().valueSubject()))
                .doesNotHaveDuplicates()
                .allSatisfy(subject -> assertThat(subject).endsWith("-value"));
        assertThat(Arrays.stream(TradingEventType.values()).map(type -> type.keySchema().getFullName()))
                .containsOnly(TradingEventSchemas.NAMESPACE + ".TradingEventKey");

        assertThat(TradingEventType.ORDER_COMMAND.route()).satisfies(route -> {
            assertThat(route.topic()).isEqualTo("trading.v1.order-command");
            assertThat(route.keySubject()).isEqualTo("trading.v1.order-command-key");
            assertThat(route.valueSubject()).isEqualTo("trading.v1.order-command-value");
            assertThat(route.deadLetterTopic()).isEqualTo("trading.v1.order-command.dlq");
        });
    }

    @Test
    void generated_record_class_schema_matches_the_checked_in_schema() throws ReflectiveOperationException {
        for (TradingEventType type : TradingEventType.values()) {
            Schema generatedSchema = generatedSchema(type);

            assertThat(type.recordClass().getPackageName()).isEqualTo(TradingEventSchemas.NAMESPACE);
            assertThat(generatedSchema.getFullName()).isEqualTo(type.avroSchema().getFullName());
            assertThat(generatedSchema).isEqualTo(type.schema().load());
        }
    }

    @Test
    void event_type_can_be_resolved_from_generated_record_class() {
        assertThat(TradingEventType.fromRecordClass(TradingEventType.ORDER_RESULT.recordClass()))
                .contains(TradingEventType.ORDER_RESULT);
    }

    private Schema generatedSchema(TradingEventType type) throws ReflectiveOperationException {
        try {
            return (Schema) type.recordClass().getMethod("getClassSchema").invoke(null);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw ex;
        }
    }
}
