package io.github.manu.messaging;

import io.github.manu.events.TradingEventType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingEventHandlerRegistryTest {

    @Test
    void fans_out_to_all_handlers_for_one_event_type() {
        List<String> handled = new ArrayList<>();
        TradingEventHandler first = ignored -> {
            handled.add("first");
            return CompletableFuture.completedFuture(null);
        };
        TradingEventHandler second = ignored -> {
            handled.add("second");
            return CompletableFuture.completedFuture(null);
        };
        TradingEventHandlerRegistry registry = new TradingEventHandlerRegistry(List.of(
                new TradingEventHandlerRegistration(TradingEventType.ORDER_COMMAND, first),
                new TradingEventHandlerRegistration(TradingEventType.ORDER_COMMAND, second)
        ));

        registry.handlerFor(TradingEventType.ORDER_COMMAND).handle(null).join();

        assertThat(handled).containsExactly("first", "second");
    }

    @Test
    void missing_handler_returns_failed_future() {
        TradingEventHandlerRegistry registry = new TradingEventHandlerRegistry(List.of());

        assertThatThrownBy(() -> registry.handlerFor(TradingEventType.ORDER_COMMAND).handle(null).join())
                .isInstanceOf(CompletionException.class)
                .hasMessageContaining("No handler registered");
    }
}
