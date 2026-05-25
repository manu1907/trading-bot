package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.v1.MarketDataEvent;
import io.github.manu.messaging.DeadLetterTradingEvent;
import io.github.manu.messaging.PublishedTradingEvent;
import io.github.manu.messaging.TradingEventBus;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceMarketDataStreamRuntimeTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-22T20:00:00Z"),
            ZoneOffset.UTC
    );
    private static final BinanceMarketDataEventMapper.Context CONTEXT =
            new BinanceMarketDataEventMapper.Context("binance", "demo", "main", "usd_m_futures");

    @Test
    void starts_combined_market_stream_and_publishes_messages() {
        FakeWebSocketTransport webSocketTransport = new FakeWebSocketTransport();
        CapturingTradingEventBus eventBus = new CapturingTradingEventBus();
        BinanceMarketDataStreamRuntime runtime = runtime(webSocketTransport, marketData(), eventBus);

        runtime.start();
        BinanceWebSocketConnection connection = runtime.activeConnection().orElseThrow();
        webSocketTransport.listeners.getFirst().onText("""
                {
                  "stream": "btcusdt@aggTrade",
                  "data": {
                    "e": "aggTrade",
                    "E": 123456789,
                    "s": "BTCUSDT",
                    "a": 5933014,
                    "p": "0.001",
                    "q": "100",
                    "T": 123456785,
                    "m": false
                  }
                }
                """);

        assertThat(connection.plan().mode()).isEqualTo(BinanceWebSocketMode.COMBINED);
        assertThat(connection.plan().route()).isEqualTo(BinanceWebSocketRoute.DEFAULT);
        assertThat(connection.plan().streams()).containsExactly("btcusdt@aggTrade");
        assertThat(eventBus.envelopes).singleElement()
                .satisfies(envelope -> assertThat(envelope.value()).isInstanceOf(MarketDataEvent.class));
    }

    @Test
    void start_is_idempotent_until_closed() {
        FakeWebSocketTransport webSocketTransport = new FakeWebSocketTransport();
        BinanceMarketDataStreamRuntime runtime = runtime(webSocketTransport, marketData(), new CapturingTradingEventBus());

        runtime.start();
        BinanceWebSocketConnection first = runtime.activeConnection().orElseThrow();
        runtime.start();
        BinanceWebSocketConnection second = runtime.activeConnection().orElseThrow();

        assertThat(second).isSameAs(first);
        assertThat(webSocketTransport.plans).hasSize(1);
    }

    @Test
    void close_shuts_active_supervisor() {
        FakeWebSocketTransport webSocketTransport = new FakeWebSocketTransport();
        RecordingListener listener = new RecordingListener();
        BinanceMarketDataStreamRuntime runtime = runtime(
                webSocketTransport,
                marketData(),
                new CapturingTradingEventBus(),
                listener
        );

        runtime.start();
        runtime.close();

        assertThat(runtime.activeConnection()).isEmpty();
        assertThat(webSocketTransport.closeCount).isEqualTo(1);
        assertThat(listener.closeCount).hasValue(0);
    }

    @Test
    void rejects_enabled_runtime_without_streams() {
        BinanceMarketDataStreamRuntime runtime = runtime(
                new FakeWebSocketTransport(),
                new BinanceProperties.MarketDataStream(true, "combined", "default", List.of()),
                new CapturingTradingEventBus()
        );

        assertThatThrownBy(runtime::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("market_data.streams");
    }

    @Test
    void supports_raw_market_route_when_configured() {
        FakeWebSocketTransport webSocketTransport = new FakeWebSocketTransport();
        BinanceMarketDataStreamRuntime runtime = runtime(
                webSocketTransport,
                new BinanceProperties.MarketDataStream(true, "raw", "market", List.of("btcusdt@markPrice")),
                new CapturingTradingEventBus()
        );

        runtime.start();
        BinanceWebSocketConnection connection = runtime.activeConnection().orElseThrow();

        assertThat(connection.plan().mode()).isEqualTo(BinanceWebSocketMode.RAW);
        assertThat(connection.plan().route()).isEqualTo(BinanceWebSocketRoute.MARKET);
        assertThat(connection.plan().uri().toString())
                .isEqualTo("wss://fstream.binance.com/market/ws/btcusdt@markPrice");
    }

    private BinanceMarketDataStreamRuntime runtime(
            FakeWebSocketTransport webSocketTransport,
            BinanceProperties.MarketDataStream marketData,
            TradingEventBus eventBus
    ) {
        return runtime(webSocketTransport, marketData, eventBus, new RecordingListener());
    }

    private BinanceMarketDataStreamRuntime runtime(
            FakeWebSocketTransport webSocketTransport,
            BinanceProperties.MarketDataStream marketData,
            TradingEventBus eventBus,
            BinanceWebSocketListener listener
    ) {
        return new BinanceMarketDataStreamRuntime(
                marketData,
                new BinanceWebSocketClient(webSocketTransport),
                new BinanceWebSocketEndpointPlanner(websocket(), FIXED_CLOCK),
                new FakeScheduler(),
                FIXED_CLOCK,
                Duration.ofSeconds(2),
                new BinanceMarketDataEventMapper(),
                CONTEXT,
                eventBus,
                listener
        );
    }

    private BinanceProperties.MarketDataStream marketData() {
        return new BinanceProperties.MarketDataStream(true, "combined", "default", List.of("btcusdt@aggTrade"));
    }

    private BinanceProperties.Websocket websocket() {
        return new BinanceProperties.Websocket(
                "wss://fstream.binance.com",
                "/public",
                "/market",
                "/private",
                "/ws",
                "/stream",
                24,
                10,
                20,
                null,
                60,
                null,
                5,
                1024,
                300,
                "MILLISECONDS",
                null,
                null
        );
    }

    private static final class FakeWebSocketTransport implements BinanceWebSocketTransport {
        private final List<BinanceWebSocketConnectionPlan> plans = new ArrayList<>();
        private final List<BinanceWebSocketListener> listeners = new ArrayList<>();
        private int closeCount;

        @Override
        public BinanceWebSocketConnection connect(
                BinanceWebSocketConnectionPlan plan,
                BinanceWebSocketListener listener
        ) {
            plans.add(plan);
            listeners.add(listener);
            listener.onOpen(plan);
            return new BinanceWebSocketConnection(
                    plan,
                    FIXED_CLOCK.instant(),
                    () -> {
                        closeCount++;
                        listener.onClose();
                    }
            );
        }
    }

    private static final class FakeScheduler implements BinanceWebSocketReconnectScheduler {

        @Override
        public BinanceScheduledTask schedule(Duration delay, Runnable task) {
            return () -> {
            };
        }
    }

    private static final class CapturingTradingEventBus implements TradingEventBus {

        private final List<TradingEventEnvelope<? extends SpecificRecord>> envelopes = new ArrayList<>();

        @Override
        public CompletableFuture<PublishedTradingEvent> publish(
                TradingEventEnvelope<? extends SpecificRecord> envelope
        ) {
            envelopes.add(envelope);
            return CompletableFuture.completedFuture(new PublishedTradingEvent(
                    envelope.eventType(),
                    envelope.route().topic(),
                    0,
                    envelopes.size()
            ));
        }

        @Override
        public CompletableFuture<PublishedTradingEvent> publishDeadLetter(DeadLetterTradingEvent event) {
            throw new UnsupportedOperationException("dead letters are not used by this runtime");
        }
    }

    private static final class RecordingListener implements BinanceWebSocketListener {

        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void onClose() {
            closeCount.incrementAndGet();
        }
    }
}
