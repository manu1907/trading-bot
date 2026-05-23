package io.github.manu.messaging;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.ConfigChangeEvent;
import io.github.manu.events.v1.ConfigChangeSource;
import io.github.manu.events.v1.TradingEventKey;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

class TradingEventDispatcherTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-23T14:00:00Z"), ZoneOffset.UTC);

    @Test
    void decodes_and_handles_received_events() {
        SchemaRegistryTradingEventCodec codec = new SchemaRegistryTradingEventCodec(new InMemorySchemaRegistryClient());
        CapturingDeadLetterPublisher deadLetterPublisher = new CapturingDeadLetterPublisher();
        TradingEventDispatcher dispatcher = new TradingEventDispatcher(codec, deadLetterPublisher, CLOCK);
        SerializedRegistryTradingEvent serialized = codec.serialize(configChangeEnvelope());
        ReceivedTradingEvent received =
                new ReceivedTradingEvent(TradingEventType.CONFIG_CHANGE, serialized.keyPayload(), serialized.valuePayload());
        CapturingHandler handler = CapturingHandler.success();

        TradingEventDispatchResult result = dispatcher.dispatch(received, handler).join();

        assertThat(result.status()).isEqualTo(TradingEventDispatchStatus.HANDLED);
        assertThat(result.eventType()).isEqualTo(TradingEventType.CONFIG_CHANGE);
        assertThat(result.reason()).isNull();
        assertThat(result.publishedDeadLetter()).isNull();
        assertThat(handler.envelope().value())
                .isInstanceOfSatisfying(ConfigChangeEvent.class, value -> {
                    assertThat(value.getChangeId().toString()).isEqualTo("cfg-001");
                    assertThat(value.getApplied()).isTrue();
                });
        assertThat(deadLetterPublisher.event()).isNull();
    }

    @Test
    void dead_letters_decode_failures_with_original_payloads() {
        SchemaRegistryTradingEventCodec codec = new SchemaRegistryTradingEventCodec(new InMemorySchemaRegistryClient());
        CapturingDeadLetterPublisher deadLetterPublisher = new CapturingDeadLetterPublisher();
        TradingEventDispatcher dispatcher = new TradingEventDispatcher(codec, deadLetterPublisher, CLOCK);
        byte[] keyPayload = new byte[] { 1, 2, 3 };
        byte[] valuePayload = new byte[] { 4, 5, 6 };
        ReceivedTradingEvent received =
                new ReceivedTradingEvent(TradingEventType.CONFIG_CHANGE, keyPayload, valuePayload);

        TradingEventDispatchResult result = dispatcher.dispatch(received, CapturingHandler.success()).join();

        assertThat(result.status()).isEqualTo(TradingEventDispatchStatus.DEAD_LETTERED);
        assertThat(result.reason()).contains("decode failed");
        assertThat(result.publishedDeadLetter().topic())
                .isEqualTo(TradingEventType.CONFIG_CHANGE.route().deadLetterTopic());
        assertThat(deadLetterPublisher.event().keyPayload()).containsExactly(keyPayload);
        assertThat(deadLetterPublisher.event().valuePayload()).containsExactly(valuePayload);
        assertThat(deadLetterPublisher.event().failedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void dead_letters_handler_failures_with_original_payloads() {
        SchemaRegistryTradingEventCodec codec = new SchemaRegistryTradingEventCodec(new InMemorySchemaRegistryClient());
        CapturingDeadLetterPublisher deadLetterPublisher = new CapturingDeadLetterPublisher();
        TradingEventDispatcher dispatcher = new TradingEventDispatcher(codec, deadLetterPublisher, CLOCK);
        SerializedRegistryTradingEvent serialized = codec.serialize(configChangeEnvelope());
        ReceivedTradingEvent received =
                new ReceivedTradingEvent(TradingEventType.CONFIG_CHANGE, serialized.keyPayload(), serialized.valuePayload());

        TradingEventDispatchResult result = dispatcher.dispatch(
                received,
                CapturingHandler.failed(new IllegalStateException("projection write failed"))
        ).join();

        assertThat(result.status()).isEqualTo(TradingEventDispatchStatus.DEAD_LETTERED);
        assertThat(result.reason()).contains("handler failed").contains("projection write failed");
        assertThat(deadLetterPublisher.event().keyPayload()).containsExactly(serialized.keyPayload());
        assertThat(deadLetterPublisher.event().valuePayload()).containsExactly(serialized.valuePayload());
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

    private static final class CapturingHandler implements TradingEventHandler {

        private final RuntimeException failure;
        private TradingEventEnvelope<?> envelope;

        private CapturingHandler(RuntimeException failure) {
            this.failure = failure;
        }

        private static CapturingHandler success() {
            return new CapturingHandler(null);
        }

        private static CapturingHandler failed(RuntimeException failure) {
            return new CapturingHandler(failure);
        }

        @Override
        public CompletableFuture<Void> handle(TradingEventEnvelope<?> envelope) {
            this.envelope = envelope;
            if (failure == null) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.failedFuture(new CompletionException(failure));
        }

        private TradingEventEnvelope<?> envelope() {
            return envelope;
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
                    12
            ));
        }

        private DeadLetterTradingEvent event() {
            return event;
        }
    }
}
