package io.github.manu.journal;

import io.github.manu.events.SerializedTradingEvent;

import java.util.List;
import java.util.function.Consumer;

public interface TradingEventJournal extends AutoCloseable {

    JournaledTradingEvent append(SerializedTradingEvent event);

    List<JournaledTradingEvent> readAll();

    default void replay(Consumer<JournaledTradingEvent> consumer) {
        readAll().forEach(consumer);
    }

    @Override
    void close();
}
