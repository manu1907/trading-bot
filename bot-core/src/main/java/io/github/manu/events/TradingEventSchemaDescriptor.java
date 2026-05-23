package io.github.manu.events;

import org.apache.avro.Schema;

public record TradingEventSchemaDescriptor(
        TradingEventType eventType,
        TradingEventRoute route,
        String keySchemaFullName,
        long keySchemaFingerprint,
        String valueSchemaFullName,
        long valueSchemaFingerprint
) {

    public static TradingEventSchemaDescriptor from(TradingEventType eventType) {
        Schema keySchema = eventType.keySchema();
        Schema valueSchema = eventType.avroSchema();
        return new TradingEventSchemaDescriptor(
                eventType,
                eventType.route(),
                keySchema.getFullName(),
                TradingEventSchemas.fingerprint(keySchema),
                valueSchema.getFullName(),
                TradingEventSchemas.fingerprint(valueSchema)
        );
    }
}
