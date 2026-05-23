package io.github.manu.events;

import io.github.manu.events.v1.TradingEventKey;
import org.apache.avro.specific.SpecificRecord;

import java.util.Objects;

public final class TradingEventMessageCodec {

    private final TradingEventCodec<TradingEventKey> keyCodec =
            TradingEventCodec.of(TradingEventSchemas.load(TradingEventSchemas.KEY_SCHEMA_FILE));

    public <T extends SpecificRecord> SerializedTradingEvent serialize(TradingEventEnvelope<T> envelope) {
        Objects.requireNonNull(envelope, "envelope");
        TradingEventCodec<T> valueCodec = TradingEventCodec.of(envelope.eventType().avroSchema());
        return new SerializedTradingEvent(
                envelope.eventType(),
                envelope.route(),
                keyCodec.encode(envelope.key()),
                valueCodec.encode(envelope.value())
        );
    }
}
