package io.github.manu.events;

import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.TradingEventKey;
import org.apache.avro.JsonProperties;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TradingEventSchemasTest {

    @Test
    void all_event_schemas_are_available_on_the_runtime_classpath() {
        Map<TradingEventSchema, Schema> schemas = TradingEventSchemas.loadAll();

        assertThat(schemas).hasSize(TradingEventSchema.values().length);
        assertThat(schemas.values()).allSatisfy(schema -> {
            assertThat(schema.getNamespace()).isEqualTo(TradingEventSchemas.NAMESPACE);
            assertThat(schema.getField("eventId")).isNotNull();
            assertThat(schema.getField("schemaVersion")).isNotNull();
            assertThat(schema.getField("attributes")).isNotNull();
        });
    }

    @Test
    void specific_record_classes_are_generated_from_the_schema_files() {
        assertThat(OrderCommandEvent.getClassSchema().getFullName())
                .isEqualTo(TradingEventSchemas.NAMESPACE + ".OrderCommandEvent");
        assertThat(TradingEventKey.getClassSchema().getFullName())
                .isEqualTo(TradingEventSchemas.NAMESPACE + ".TradingEventKey");
    }

    @Test
    void schemas_are_self_compatible_and_allow_optional_field_addition() {
        for (TradingEventSchema eventSchema : TradingEventSchema.values()) {
            Schema writer = eventSchema.load();
            Schema reader = addOptionalCompatibilityProbe(writer);

            assertCompatible(writer, writer);
            assertCompatible(reader, writer);
            assertCompatible(writer, reader);
        }
    }

    @Test
    void nullable_fields_declare_null_defaults_for_schema_evolution() {
        for (Schema schema : TradingEventSchemas.loadAll().values()) {
            assertThat(schema.getFields())
                    .filteredOn(field -> isNullable(field.schema()))
                    .allSatisfy(field -> assertThat(field.defaultVal())
                            .as(schema.getName() + "." + field.name())
                            .isEqualTo(JsonProperties.NULL_VALUE));
        }
    }

    @Test
    void order_command_event_round_trips_with_expected_values() throws IOException {
        Schema schema = TradingEventSchema.ORDER_COMMAND.load();
        GenericRecord original = new GenericData.Record(schema);
        original.put("eventId", "evt-001");
        original.put("schemaVersion", 1);
        original.put("commandId", "cmd-001");
        original.put("strategyId", "lfa");
        original.put("provider", "binance");
        original.put("environment", "demo");
        original.put("account", "main");
        original.put("market", "usdm_futures");
        original.put("symbol", "BTCUSDT");
        original.put("action", enumValue(schema.getField("action").schema(), "NEW"));
        original.put("targetClientOrderId", null);
        original.put("targetExchangeOrderId", null);
        original.put("side", enumValue(schema.getField("side").schema(), "BUY"));
        original.put("orderType", enumValue(schema.getField("orderType").schema(), "LIMIT"));
        original.put("positionSide", enumValue(nullableValueSchema(schema.getField("positionSide").schema()), "LONG"));
        original.put("timeInForce", enumValue(nullableValueSchema(schema.getField("timeInForce").schema()), "GTC"));
        original.put("quantity", "0.001");
        original.put("quoteOrderQuantity", null);
        original.put("price", "50000.10");
        original.put("stopPrice", null);
        original.put("activationPrice", null);
        original.put("callbackRate", null);
        original.put("reduceOnly", false);
        original.put("closePosition", false);
        original.put("clientOrderId", "tb-lfa-001");
        original.put("idempotencyKey", "idem-001");
        original.put("requestedAtMicros", 1_800_000_000_000_000L);
        original.put("attributes", Map.of("orderResponseType", "RESULT"));

        GenericRecord decoded = roundTrip(schema, original);

        assertThat(decoded.get("commandId").toString()).isEqualTo("cmd-001");
        assertThat(decoded.get("symbol").toString()).isEqualTo("BTCUSDT");
        assertThat(decoded.get("action").toString()).isEqualTo("NEW");
        assertThat(decoded.get("side").toString()).isEqualTo("BUY");
        assertThat(decoded.get("quantity").toString()).isEqualTo("0.001");
        assertThat(decoded.get("price").toString()).isEqualTo("50000.10");
        assertThat(decoded.get("requestedAtMicros")).isEqualTo(1_800_000_000_000_000L);
        assertThat(decoded.get("attributes").toString()).contains("orderResponseType=RESULT");
    }

    private void assertCompatible(Schema reader, Schema writer) {
        SchemaCompatibility.SchemaCompatibilityResult result =
                SchemaCompatibility.checkReaderWriterCompatibility(reader, writer).getResult();
        assertThat(result.getCompatibility())
                .as(reader.getName() + " reads " + writer.getName())
                .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
    }

    private Schema addOptionalCompatibilityProbe(Schema schema) {
        String evolved = schema.toString().replace(
                "\"fields\":[",
                "\"fields\":[{\"name\":\"compatibilityProbe\",\"type\":[\"null\",\"string\"],\"default\":null},"
        );
        return new Schema.Parser().parse(evolved);
    }

    private boolean isNullable(Schema schema) {
        return schema.isUnion() && schema.getTypes().stream().anyMatch(type -> type.getType() == Schema.Type.NULL);
    }

    private Schema nullableValueSchema(Schema schema) {
        return schema.getTypes().stream()
                .filter(type -> type.getType() != Schema.Type.NULL)
                .findFirst()
                .orElseThrow();
    }

    private GenericData.EnumSymbol enumValue(Schema schema, String value) {
        return new GenericData.EnumSymbol(schema, value);
    }

    private GenericRecord roundTrip(Schema schema, GenericRecord original) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(output, null);
        new GenericDatumWriter<GenericRecord>(schema).write(original, encoder);
        encoder.flush();

        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(output.toByteArray(), null);
        return new GenericDatumReader<GenericRecord>(schema).read(null, decoder);
    }
}
