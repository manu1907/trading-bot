package io.github.manu.events;

import org.apache.avro.Schema;
import org.apache.avro.data.TimeConversions;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

public final class TradingEventCodec<T extends SpecificRecord> {

    private final Schema schema;
    private final SpecificData specificData;

    private TradingEventCodec(Schema schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.specificData = new SpecificData();
        this.specificData.addLogicalTypeConversion(new TimeConversions.TimestampMicrosConversion());
    }

    public static <T extends SpecificRecord> TradingEventCodec<T> of(Schema schema) {
        return new TradingEventCodec<>(schema);
    }

    public byte[] encode(T event) {
        Objects.requireNonNull(event, "event");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(output, null);
        try {
            new SpecificDatumWriter<T>(schema, specificData).write(event, encoder);
            encoder.flush();
        } catch (IOException ex) {
            throw new TradingEventCodecException("Failed to encode " + schema.getFullName(), ex);
        }
        return output.toByteArray();
    }

    public T decode(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(payload, null);
        try {
            return new SpecificDatumReader<T>(schema, schema, specificData).read(null, decoder);
        } catch (IOException | RuntimeException ex) {
            throw new TradingEventCodecException("Failed to decode " + schema.getFullName(), ex);
        }
    }
}
