package io.github.manu.events;

import org.apache.avro.Schema;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

public final class TradingEventSchemas {

    public static final String NAMESPACE = "io.github.manu.events.v1";

    private static final String RESOURCE_BASE = "/io/github/manu/events/v1/";

    private TradingEventSchemas() {
    }

    public static Schema load(String fileName) {
        String resourceName = RESOURCE_BASE + fileName;
        try (InputStream input = TradingEventSchemas.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalArgumentException("Trading event schema not found: " + resourceName);
            }
            return new Schema.Parser().parse(input);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load trading event schema: " + resourceName, ex);
        }
    }

    public static Map<TradingEventSchema, Schema> loadAll() {
        EnumMap<TradingEventSchema, Schema> schemas = new EnumMap<>(TradingEventSchema.class);
        for (TradingEventSchema schema : TradingEventSchema.values()) {
            schemas.put(schema, schema.load());
        }
        return Map.copyOf(schemas);
    }
}
