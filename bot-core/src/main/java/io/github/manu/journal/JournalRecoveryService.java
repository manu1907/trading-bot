package io.github.manu.journal;

import io.github.manu.events.SerializedTradingEvent;
import io.github.manu.events.TradingEventCodec;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.TradingEventKey;
import io.github.manu.messaging.TradingEventHandler;
import io.github.manu.messaging.TradingEventHandlerRegistry;
import org.apache.avro.specific.SpecificRecord;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;

public final class JournalRecoveryService {

    private final TradingEventJournal journal;
    private final TradingEventHandlerRegistry handlerRegistry;

    public JournalRecoveryService(
            TradingEventJournal journal,
            TradingEventHandlerRegistry handlerRegistry
    ) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.handlerRegistry = Objects.requireNonNull(handlerRegistry, "handlerRegistry");
    }

    public JournalRecoveryReport replayAll() {
        List<JournaledTradingEvent> events = journal.readAll();
        long lastIndex = -1;
        int replayedEvents = 0;
        for (JournaledTradingEvent event : events) {
            replay(event);
            replayedEvents++;
            lastIndex = event.index();
        }
        return new JournalRecoveryReport(replayedEvents, lastIndex);
    }

    private void replay(JournaledTradingEvent journaled) {
        try {
            TradingEventEnvelope<?> envelope = decode(journaled.event());
            TradingEventHandler handler = handlerRegistry.replayHandlerFor(envelope.eventType());
            handler.handle(envelope).join();
        } catch (RuntimeException ex) {
            throw new JournalException("Failed to replay journal event at index " + journaled.index(), unwrap(ex));
        }
    }

    private static TradingEventEnvelope<?> decode(SerializedTradingEvent serialized) {
        TradingEventType eventType = serialized.eventType();
        TradingEventKey key = TradingEventCodec.<TradingEventKey>of(eventType.keySchema())
                .decode(serialized.keyPayload());
        SpecificRecord value = TradingEventCodec.<SpecificRecord>of(eventType.avroSchema())
                .decode(serialized.valuePayload());
        return TradingEventEnvelope.of(eventType, key, value);
    }

    private static Throwable unwrap(Throwable exception) {
        if (exception instanceof CompletionException && exception.getCause() != null) {
            return exception.getCause();
        }
        return exception;
    }
}
