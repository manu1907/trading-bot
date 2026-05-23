package io.github.manu.messaging;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.ConfigChangeEvent;
import io.github.manu.events.v1.ConfigChangeSource;
import io.github.manu.events.v1.TradingEventKey;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingEventConsumerServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-23T15:00:00Z"), ZoneOffset.UTC);

    @Test
    void dispatches_polled_events_and_commits_after_successful_handling() {
        SchemaRegistryTradingEventCodec codec = new SchemaRegistryTradingEventCodec(new InMemorySchemaRegistryClient());
        CapturingDeadLetterPublisher deadLetterPublisher = new CapturingDeadLetterPublisher();
        CapturingRecordConsumer consumer = new CapturingRecordConsumer();
        CapturingHandler handler = new CapturingHandler();
        TradingEventConsumerService service = new TradingEventConsumerService(
                consumer,
                new TradingEventDispatcher(codec, deadLetterPublisher, CLOCK),
                new TradingEventHandlerRegistry(List.of(
                        new TradingEventHandlerRegistration(TradingEventType.CONFIG_CHANGE, handler)
                ))
        );
        SerializedRegistryTradingEvent serialized = codec.serialize(configChangeEnvelope());
        consumer.add(new ReceivedTradingEvent(
                TradingEventType.CONFIG_CHANGE,
                serialized.keyPayload(),
                serialized.valuePayload()
        ));

        List<TradingEventDispatchResult> results = service.pollAndDispatch(Duration.ofMillis(50));

        assertThat(results).singleElement()
                .extracting(TradingEventDispatchResult::status)
                .isEqualTo(TradingEventDispatchStatus.HANDLED);
        assertThat(handler.handled()).hasSize(1);
        assertThat(consumer.committed()).isTrue();
        assertThat(deadLetterPublisher.event()).isNull();
    }

    @Test
    void dead_letters_when_no_handler_is_registered_and_still_commits() {
        SchemaRegistryTradingEventCodec codec = new SchemaRegistryTradingEventCodec(new InMemorySchemaRegistryClient());
        CapturingDeadLetterPublisher deadLetterPublisher = new CapturingDeadLetterPublisher();
        CapturingRecordConsumer consumer = new CapturingRecordConsumer();
        TradingEventConsumerService service = new TradingEventConsumerService(
                consumer,
                new TradingEventDispatcher(codec, deadLetterPublisher, CLOCK),
                new TradingEventHandlerRegistry(List.of())
        );
        SerializedRegistryTradingEvent serialized = codec.serialize(configChangeEnvelope());
        consumer.add(new ReceivedTradingEvent(
                TradingEventType.CONFIG_CHANGE,
                serialized.keyPayload(),
                serialized.valuePayload()
        ));

        List<TradingEventDispatchResult> results = service.pollAndDispatch(Duration.ofMillis(50));

        assertThat(results).singleElement()
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo(TradingEventDispatchStatus.DEAD_LETTERED);
                    assertThat(result.reason()).contains("No handler registered");
                });
        assertThat(deadLetterPublisher.event().eventType()).isEqualTo(TradingEventType.CONFIG_CHANGE);
        assertThat(consumer.committed()).isTrue();
    }

    @Test
    void does_not_commit_when_dispatch_future_fails() {
        CapturingRecordConsumer consumer = new CapturingRecordConsumer();
        TradingEventDispatcher failingDispatcher = new TradingEventDispatcher(
                new SchemaRegistryTradingEventCodec(new InMemorySchemaRegistryClient()),
                event -> CompletableFuture.failedFuture(new MessagingException("dlq unavailable")),
                CLOCK
        );
        TradingEventConsumerService service = new TradingEventConsumerService(
                consumer,
                failingDispatcher,
                new TradingEventHandlerRegistry(List.of())
        );
        consumer.add(new ReceivedTradingEvent(
                TradingEventType.CONFIG_CHANGE,
                new byte[] { 1 },
                new byte[] { 2 }
        ));

        assertThatThrownBy(() -> service.pollAndDispatch(Duration.ofMillis(50)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("dlq unavailable");
        assertThat(consumer.committed()).isFalse();
    }

    private static TradingEventEnvelope<ConfigChangeEvent> configChangeEnvelope() {
        TradingEventKey key = TradingEventKeys.config(
                TradingEventType.CONFIG_CHANGE,
                "binance",
                "demo",
                "main",
                "usdm_futures",
                "/providers/binance/environments/demo/accounts/main/enabled"
        );
        ConfigChangeEvent event = ConfigChangeEvent.newBuilder()
                .setEventId("evt-config")
                .setSchemaVersion(1)
                .setChangeId("cfg-001")
                .setSource(ConfigChangeSource.RUNTIME_FILE)
                .setProfile("live")
                .setProvider("binance")
                .setEnvironment("demo")
                .setAccount("main")
                .setMarket("usdm_futures")
                .setPath("/providers/binance/environments/demo/accounts/main/enabled")
                .setOldValue("false")
                .setNewValue("true")
                .setApplied(true)
                .setRejectedReason(null)
                .setChangedAtMicros(Instant.parse("2026-05-23T11:00:00Z"))
                .setAttributes(Map.of())
                .build();
        return TradingEventEnvelope.of(TradingEventType.CONFIG_CHANGE, key, event);
    }

    private static final class CapturingRecordConsumer implements TradingEventRecordConsumer {

        private final List<ReceivedTradingEvent> events = new ArrayList<>();
        private boolean committed;

        private void add(ReceivedTradingEvent event) {
            events.add(event);
        }

        @Override
        public List<ReceivedTradingEvent> poll(Duration timeout) {
            return List.copyOf(events);
        }

        @Override
        public void commitProcessed() {
            committed = true;
        }

        @Override
        public void close() {
        }

        private boolean committed() {
            return committed;
        }
    }

    private static final class CapturingHandler implements TradingEventHandler {

        private final List<TradingEventEnvelope<?>> handled = new ArrayList<>();

        @Override
        public CompletableFuture<Void> handle(TradingEventEnvelope<?> envelope) {
            handled.add(envelope);
            return CompletableFuture.completedFuture(null);
        }

        private List<TradingEventEnvelope<?>> handled() {
            return List.copyOf(handled);
        }
    }

    private static final class CapturingDeadLetterPublisher implements DeadLetterPublisher {

        private DeadLetterTradingEvent event;

        @Override
        public CompletableFuture<PublishedTradingEvent> publishAsync(DeadLetterTradingEvent event) {
            this.event = event;
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    event.eventType(),
                    event.route().deadLetterTopic(),
                    0,
                    3
            ));
        }

        private DeadLetterTradingEvent event() {
            return event;
        }
    }
}
