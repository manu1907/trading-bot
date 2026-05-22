package io.github.manu.exchange.binance;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class BinanceExecutorWebSocketReconnectScheduler implements BinanceWebSocketReconnectScheduler {

    private final ScheduledExecutorService executor;

    BinanceExecutorWebSocketReconnectScheduler(ScheduledExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor is required");
    }

    @Override
    public BinanceScheduledTask schedule(Duration delay, Runnable task) {
        Objects.requireNonNull(delay, "delay is required");
        Objects.requireNonNull(task, "task is required");

        long delayMillis = Math.max(0L, delay.toMillis());
        ScheduledFuture<?> future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }
}
