package io.github.manu.exchange.binance;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class BinanceWebSocketConnection implements AutoCloseable {

    private final BinanceWebSocketConnectionPlan plan;
    private final Instant openedAt;
    private final Runnable closeAction;
    private final Consumer<String> sendAction;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    BinanceWebSocketConnection(
            BinanceWebSocketConnectionPlan plan,
            Instant openedAt,
            Runnable closeAction
    ) {
        this(plan, openedAt, closeAction, ignored -> {
        });
    }

    BinanceWebSocketConnection(
            BinanceWebSocketConnectionPlan plan,
            Instant openedAt,
            Runnable closeAction,
            Consumer<String> sendAction
    ) {
        this.plan = Objects.requireNonNull(plan, "plan is required");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt is required");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction is required");
        this.sendAction = Objects.requireNonNull(sendAction, "sendAction is required");
    }

    BinanceWebSocketConnectionPlan plan() {
        return plan;
    }

    Instant openedAt() {
        return openedAt;
    }

    boolean isClosed() {
        return closed.get();
    }

    boolean shouldReconnect(Instant now) {
        Objects.requireNonNull(now, "now is required");
        return !isClosed() && !now.isBefore(plan.reconnectAt());
    }

    void sendText(String text) {
        if (isClosed()) {
            throw new IllegalStateException("Cannot send on a closed Binance websocket connection");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        sendAction.accept(text);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeAction.run();
        }
    }
}
