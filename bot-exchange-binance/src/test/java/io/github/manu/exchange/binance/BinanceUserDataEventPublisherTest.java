package io.github.manu.exchange.binance;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.ExecutionReportEvent;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceUserDataEventPublisherTest {

    private static final BinanceUserDataEventMapper.Context CONTEXT =
            new BinanceUserDataEventMapper.Context("binance", "demo", "main", "spot");

    private final RecordingListener delegate = new RecordingListener();
    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
    private final BinanceUserDataEventPublisher publisher = new BinanceUserDataEventPublisher(
            new BinanceUserDataEventMapper(),
            CONTEXT,
            eventBus,
            delegate
    );

    @Test
    void publishes_mapped_user_data_events_to_core_bus() {
        publisher.onText("""
                {
                  "e": "executionReport",
                  "E": 1499405658658,
                  "s": "ETHBTC",
                  "c": "tb-1",
                  "S": "BUY",
                  "o": "LIMIT",
                  "x": "TRADE",
                  "X": "FILLED",
                  "i": 4293153,
                  "l": "0.10000000",
                  "z": "0.10000000",
                  "L": "0.10264410",
                  "T": 1499405658657,
                  "t": 123,
                  "I": 8641984,
                  "m": false
                }
                """);

        assertThat(eventBus.envelopes).hasSize(1);
        TradingEventEnvelope<?> envelope = eventBus.envelopes.getFirst();
        assertThat(envelope.eventType()).isEqualTo(TradingEventType.EXECUTION_REPORT);
        assertThat(envelope.value()).isInstanceOf(ExecutionReportEvent.class);
        assertThat(delegate.errors).isEmpty();
    }

    @Test
    void ignores_unknown_user_data_events_without_publishing() {
        publisher.onText("""
                {
                  "e": "listenKeyExpired",
                  "E": 1728973001334
                }
                """);

        assertThat(eventBus.envelopes).isEmpty();
        assertThat(delegate.errors).isEmpty();
    }

    @Test
    void reports_parse_failures_to_delegate_listener() {
        publisher.onText("{\"E\":1499405658658}");

        assertThat(eventBus.envelopes).isEmpty();
        assertThat(delegate.errors).singleElement()
                .satisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error).hasMessageContaining("missing e");
                });
    }

    @Test
    void reports_publish_failures_to_delegate_listener() {
        RuntimeException failure = new RuntimeException("publish failed");
        eventBus.nextFailure = failure;

        publisher.onText("""
                {
                  "e": "balanceUpdate",
                  "E": 1573200697110,
                  "a": "BTC",
                  "d": "100.00000000",
                  "T": 1573200697068
                }
                """);

        assertThat(eventBus.envelopes).hasSize(1);
        assertThat(delegate.errors).containsExactly(failure);
    }

    @Test
    void forwards_lifecycle_callbacks_to_delegate_listener() {
        BinanceWebSocketConnectionPlan plan = plan();
        RuntimeException failure = new RuntimeException("socket failed");

        publisher.onOpen(plan);
        publisher.onError(failure);
        publisher.onClose();

        assertThat(delegate.openedPlans).containsExactly(plan);
        assertThat(delegate.errors).containsExactly(failure);
        assertThat(delegate.closeCount).hasValue(1);
    }

    private BinanceWebSocketConnectionPlan plan() {
        Instant createdAt = Instant.parse("2026-05-22T20:00:00Z");
        return new BinanceWebSocketConnectionPlan(
                URI.create("wss://fstream.binance.com/ws/listen-key"),
                BinanceWebSocketMode.RAW,
                BinanceWebSocketRoute.PRIVATE,
                List.of("listen-key"),
                createdAt,
                createdAt.plus(Duration.ofHours(24)),
                createdAt.plus(Duration.ofHours(23).plusMinutes(50)),
                Duration.ofMinutes(3),
                Duration.ofMinutes(10),
                10
        );
    }

    private static final class CapturingTradingEventBus implements TradingEventBus {

        private final List<TradingEventEnvelope<? extends SpecificRecord>> envelopes = new ArrayList<>();
        private RuntimeException nextFailure;

        @Override
        public CompletableFuture<PublishedTradingEvent> publish(
                TradingEventEnvelope<? extends SpecificRecord> envelope
        ) {
            envelopes.add(envelope);
            if (nextFailure != null) {
                CompletableFuture<PublishedTradingEvent> failed = new CompletableFuture<>();
                failed.completeExceptionally(nextFailure);
                nextFailure = null;
                return failed;
            }
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    envelope.eventType(),
                    envelope.route().topic(),
                    0,
                    envelopes.size()
            ));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this publisher");
        }
    }

    private static final class RecordingListener implements BinanceWebSocketListener {

        private final List<BinanceWebSocketConnectionPlan> openedPlans = new ArrayList<>();
        private final List<Throwable> errors = new ArrayList<>();
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void onOpen(BinanceWebSocketConnectionPlan plan) {
            openedPlans.add(plan);
        }

        @Override
        public void onError(Throwable error) {
            errors.add(error);
        }

        @Override
        public void onClose() {
            closeCount.incrementAndGet();
        }
    }
}
