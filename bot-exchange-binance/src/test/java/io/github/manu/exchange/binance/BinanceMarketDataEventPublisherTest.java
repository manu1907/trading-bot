package io.github.manu.exchange.binance;

import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.MarketDataEvent;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceMarketDataEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-05-25T10:45:00Z");
    private static final BinanceMarketDataEventMapper.Context CONTEXT =
            new BinanceMarketDataEventMapper.Context("binance", "demo", "main", "spot");

    private final RecordingListener delegate = new RecordingListener();
    private final CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
    private final BinanceMarketDataEventPublisher publisher = new BinanceMarketDataEventPublisher(
            new BinanceMarketDataEventMapper(),
            CONTEXT,
            eventBus,
            delegate,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void publishes_mapped_market_data_events_to_core_bus() {
        publisher.onText("""
                {
                  "u": 400900217,
                  "s": "BNBUSDT",
                  "b": "25.35190000",
                  "B": "31.21000000",
                  "a": "25.36520000",
                  "A": "40.66000000"
                }
                """);

        assertThat(eventBus.envelopes).hasSize(1);
        TradingEventEnvelope<?> envelope = eventBus.envelopes.getFirst();
        assertThat(envelope.eventType()).isEqualTo(TradingEventType.MARKET_DATA);
        assertThat(envelope.value()).isInstanceOf(MarketDataEvent.class);
        assertThat(((MarketDataEvent) envelope.value()).getOccurredAtMicros()).isEqualTo(NOW);
        assertThat(delegate.errors).isEmpty();
    }

    @Test
    void ignores_unknown_market_data_events_without_publishing() {
        publisher.onText("""
                {
                  "e": "serverShutdown",
                  "E": 1770123456789
                }
                """);

        assertThat(eventBus.envelopes).isEmpty();
        assertThat(delegate.errors).isEmpty();
    }

    @Test
    void reports_parse_failures_to_delegate_listener() {
        publisher.onText("{\"lastUpdateId\":160,\"bids\":[],\"asks\":[]}");

        assertThat(eventBus.envelopes).isEmpty();
        assertThat(delegate.errors).singleElement()
                .satisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error).hasMessageContaining("missing symbol");
                });
    }

    @Test
    void reports_publish_failures_to_delegate_listener() {
        RuntimeException failure = new RuntimeException("publish failed");
        eventBus.nextFailure = failure;

        publisher.onText("""
                {
                  "e": "trade",
                  "E": 1672515782136,
                  "s": "BNBBTC",
                  "t": 12345,
                  "p": "0.001",
                  "q": "100",
                  "T": 1672515782136,
                  "m": true
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
        Instant createdAt = Instant.parse("2026-05-25T10:40:00Z");
        return new BinanceWebSocketConnectionPlan(
                URI.create("wss://fstream.binance.com/market/stream?streams=btcusdt@aggTrade"),
                BinanceWebSocketMode.COMBINED,
                BinanceWebSocketRoute.MARKET,
                List.of("btcusdt@aggTrade"),
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
