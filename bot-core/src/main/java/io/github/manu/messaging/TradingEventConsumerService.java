package io.github.manu.messaging;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class TradingEventConsumerService implements TradingEventPoller, AutoCloseable {

    private final TradingEventRecordConsumer consumer;
    private final TradingEventDispatcher dispatcher;
    private final TradingEventHandlerRegistry handlerRegistry;

    public TradingEventConsumerService(
            TradingEventRecordConsumer consumer,
            TradingEventDispatcher dispatcher,
            TradingEventHandlerRegistry handlerRegistry
    ) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.handlerRegistry = Objects.requireNonNull(handlerRegistry, "handlerRegistry");
    }

    @Override
    public List<TradingEventDispatchResult> pollAndDispatch(Duration timeout) {
        List<ReceivedTradingEvent> receivedEvents = consumer.poll(Objects.requireNonNull(timeout, "timeout"));
        if (receivedEvents.isEmpty()) {
            return List.of();
        }

        List<CompletableFuture<TradingEventDispatchResult>> dispatches = receivedEvents.stream()
                .map(event -> dispatcher.dispatch(event, handlerRegistry.handlerFor(event.eventType())))
                .toList();
        List<TradingEventDispatchResult> results = dispatches.stream()
                .map(CompletableFuture::join)
                .toList();
        consumer.commitProcessed();
        return results;
    }

    @Override
    public void close() {
        consumer.close();
    }
}
