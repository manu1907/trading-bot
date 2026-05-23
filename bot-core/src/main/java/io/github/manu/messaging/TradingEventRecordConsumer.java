package io.github.manu.messaging;

import java.time.Duration;
import java.util.List;

public interface TradingEventRecordConsumer extends AutoCloseable {

    List<ReceivedTradingEvent> poll(Duration timeout);

    void commitProcessed();

    @Override
    void close();
}
