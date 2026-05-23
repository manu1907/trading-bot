package io.github.manu.journal;

import io.github.manu.events.SerializedTradingEvent;
import io.github.manu.events.TradingEventRoute;
import io.github.manu.events.TradingEventType;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.Wire;
import net.openhft.chronicle.wire.WireOut;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ChronicleTradingEventJournal implements TradingEventJournal {

    private static final String EVENT_TYPE = "eventType";
    private static final String TOPIC = "topic";
    private static final String KEY_PAYLOAD = "keyPayload";
    private static final String VALUE_PAYLOAD = "valuePayload";

    private final ChronicleQueue queue;
    private final ExcerptAppender appender;

    public ChronicleTradingEventJournal(Path directory) {
        Objects.requireNonNull(directory, "directory");
        this.queue = ChronicleQueue.singleBuilder(directory).build();
        this.appender = queue.createAppender();
    }

    @Override
    public synchronized JournaledTradingEvent append(SerializedTradingEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            appender.writeDocument(wire -> writeEvent(wire, event));
            return new JournaledTradingEvent(appender.lastIndexAppended(), event);
        } catch (RuntimeException ex) {
            throw new JournalException("Failed to append trading event to journal", ex);
        }
    }

    @Override
    public List<JournaledTradingEvent> readAll() {
        List<JournaledTradingEvent> events = new ArrayList<>();
        ExcerptTailer tailer = queue.createTailer().toStart();
        while (true) {
            try (DocumentContext document = tailer.readingDocument()) {
                if (!document.isPresent()) {
                    return List.copyOf(events);
                }
                events.add(readEvent(document));
            } catch (RuntimeException ex) {
                throw new JournalException("Failed to read trading event journal", ex);
            }
        }
    }

    @Override
    public void close() {
        queue.close();
    }

    private static void writeEvent(WireOut wire, SerializedTradingEvent event) {
        wire.write(EVENT_TYPE).text(event.eventType().name());
        wire.write(TOPIC).text(event.route().topic());
        wire.write(KEY_PAYLOAD).bytes(event.keyPayload());
        wire.write(VALUE_PAYLOAD).bytes(event.valuePayload());
    }

    private static JournaledTradingEvent readEvent(DocumentContext document) {
        Wire wire = document.wire();
        if (wire == null) {
            throw new JournalException("Journal document has no wire payload");
        }
        String eventTypeName = wire.read(EVENT_TYPE).text();
        String topic = wire.read(TOPIC).text();
        byte[] keyPayload = wire.read(KEY_PAYLOAD).bytes();
        byte[] valuePayload = wire.read(VALUE_PAYLOAD).bytes();
        if (eventTypeName == null || eventTypeName.isBlank()) {
            throw new JournalException("Journal field " + EVENT_TYPE + " is missing");
        }
        if (topic == null || topic.isBlank()) {
            throw new JournalException("Journal field " + TOPIC + " is missing");
        }
        if (keyPayload == null || keyPayload.length == 0) {
            throw new JournalException("Journal field " + KEY_PAYLOAD + " is missing");
        }
        if (valuePayload == null || valuePayload.length == 0) {
            throw new JournalException("Journal field " + VALUE_PAYLOAD + " is missing");
        }
        TradingEventType eventType = TradingEventType.valueOf(eventTypeName);
        TradingEventRoute route = eventType.route();
        if (!route.topic().equals(topic)) {
            throw new JournalException("Journal topic " + topic + " does not match event type " + eventType);
        }
        return new JournaledTradingEvent(
                document.index(),
                new SerializedTradingEvent(eventType, route, keyPayload, valuePayload)
        );
    }
}
