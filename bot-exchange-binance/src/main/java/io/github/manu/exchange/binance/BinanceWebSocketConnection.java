package io.github.manu.exchange.binance;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class BinanceWebSocketConnection implements AutoCloseable {

    private final BinanceWebSocketConnectionPlan plan;
    private final Instant openedAt;
    private final Runnable closeAction;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    BinanceWebSocketConnection(
            BinanceWebSocketConnectionPlan plan,
            Instant openedAt,
            Runnable closeAction
    ) {
        this.plan = Objects.requireNonNull(plan, "plan is required");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt is required");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction is required");
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

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeAction.run();
        }
    }
}
