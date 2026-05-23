package io.github.manu.messaging;

import io.github.manu.events.TradingEventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingEventHandlerRegistryTest {

    @Test
    void rejects_duplicate_handlers_for_one_event_type() {
        TradingEventHandler handler = ignored -> CompletableFuture.completedFuture(null);

        assertThatThrownBy(() -> new TradingEventHandlerRegistry(List.of(
                new TradingEventHandlerRegistration(TradingEventType.ORDER_COMMAND, handler),
                new TradingEventHandlerRegistration(TradingEventType.ORDER_COMMAND, handler)
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate handler");
    }

    @Test
    void missing_handler_returns_failed_future() {
        TradingEventHandlerRegistry registry = new TradingEventHandlerRegistry(List.of());

        assertThatThrownBy(() -> registry.handlerFor(TradingEventType.ORDER_COMMAND).handle(null).join())
                .isInstanceOf(CompletionException.class)
                .hasMessageContaining("No handler registered");
    }
}
