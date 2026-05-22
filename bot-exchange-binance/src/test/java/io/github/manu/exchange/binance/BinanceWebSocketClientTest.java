package io.github.manu.exchange.binance;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceWebSocketClientTest {

    @Test
    void connects_through_transport_and_forwards_lifecycle_callbacks() {
        FakeWebSocketTransport transport = new FakeWebSocketTransport();
        RecordingWebSocketListener listener = new RecordingWebSocketListener();
        BinanceWebSocketClient client = new BinanceWebSocketClient(transport);
        BinanceWebSocketConnectionPlan plan = plan();

        BinanceWebSocketConnection connection = client.connect(plan, listener);
        transport.emitText("market-event");
        RuntimeException error = new RuntimeException("socket failed");
        transport.emitError(error);

        assertThat(transport.connectedPlan).isEqualTo(plan);
        assertThat(connection.plan()).isEqualTo(plan);
        assertThat(connection.openedAt()).isEqualTo(Instant.parse("2026-05-22T20:00:01Z"));
        assertThat(listener.openedPlans).containsExactly(plan);
        assertThat(listener.textMessages).containsExactly("market-event");
        assertThat(listener.errors).containsExactly(error);

        connection.close();
        connection.close();

        assertThat(connection.isClosed()).isTrue();
        assertThat(transport.closeCount).hasValue(1);
        assertThat(listener.closeCount).hasValue(1);
    }

    @Test
    void reports_reconnect_due_from_connection_plan() {
        BinanceWebSocketConnection connection = new BinanceWebSocketConnection(
                plan(),
                Instant.parse("2026-05-22T20:00:01Z"),
                () -> {
                }
        );

        assertThat(connection.shouldReconnect(Instant.parse("2026-05-23T19:49:59Z"))).isFalse();
        assertThat(connection.shouldReconnect(Instant.parse("2026-05-23T19:50:00Z"))).isTrue();

        connection.close();

        assertThat(connection.shouldReconnect(Instant.parse("2026-05-23T19:50:00Z"))).isFalse();
    }

    @Test
    void rejects_missing_inputs_and_missing_transport_connection() {
        FakeWebSocketTransport transport = new FakeWebSocketTransport();
        BinanceWebSocketClient client = new BinanceWebSocketClient(transport);

        assertThatThrownBy(() -> new BinanceWebSocketClient(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("transport");
        assertThatThrownBy(() -> client.connect(null, new RecordingWebSocketListener()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("plan");
        assertThatThrownBy(() -> client.connect(plan(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("listener");

        transport.returnNullConnection = true;

        assertThatThrownBy(() -> client.connect(plan(), new RecordingWebSocketListener()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("transport returned no connection");
    }

    private BinanceWebSocketConnectionPlan plan() {
        Instant createdAt = Instant.parse("2026-05-22T20:00:00Z");
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

    private static final class FakeWebSocketTransport implements BinanceWebSocketTransport {

        private final AtomicInteger closeCount = new AtomicInteger();
        private BinanceWebSocketConnectionPlan connectedPlan;
        private BinanceWebSocketListener listener;
        private boolean returnNullConnection;

        @Override
        public BinanceWebSocketConnection connect(
                BinanceWebSocketConnectionPlan plan,
                BinanceWebSocketListener listener
        ) {
            this.connectedPlan = plan;
            this.listener = listener;
            if (returnNullConnection) {
                return null;
            }
            listener.onOpen(plan);
            return new BinanceWebSocketConnection(
                    plan,
                    Instant.parse("2026-05-22T20:00:01Z"),
                    () -> {
                        closeCount.incrementAndGet();
                        listener.onClose();
                    }
            );
        }

        private void emitText(String text) {
            listener.onText(text);
        }

        private void emitError(Throwable error) {
            listener.onError(error);
        }
    }

    private static final class RecordingWebSocketListener implements BinanceWebSocketListener {

        private final List<BinanceWebSocketConnectionPlan> openedPlans = new ArrayList<>();
        private final List<String> textMessages = new ArrayList<>();
        private final List<Throwable> errors = new ArrayList<>();
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void onOpen(BinanceWebSocketConnectionPlan plan) {
            openedPlans.add(plan);
        }

        @Override
        public void onText(String text) {
            textMessages.add(text);
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
