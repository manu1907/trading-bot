package io.github.manu.messaging;

import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TradingEventConsumerLoop implements SmartLifecycle {

    private final TradingEventPoller poller;
    private final Duration pollTimeout;
    private final boolean autoStartup;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private Future<?> task;

    public TradingEventConsumerLoop(
            TradingEventPoller poller,
            Duration pollTimeout,
            boolean autoStartup
    ) {
        this(poller, pollTimeout, autoStartup, Executors.newSingleThreadExecutor());
    }

    TradingEventConsumerLoop(
            TradingEventPoller poller,
            Duration pollTimeout,
            boolean autoStartup,
            ExecutorService executor
    ) {
        this.poller = Objects.requireNonNull(poller, "poller");
        this.pollTimeout = Objects.requireNonNull(pollTimeout, "pollTimeout");
        if (pollTimeout.isZero() || pollTimeout.isNegative()) {
            throw new IllegalArgumentException("pollTimeout must be positive");
        }
        this.autoStartup = autoStartup;
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            task = executor.submit(this::runLoop);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false) && task != null) {
            task.cancel(true);
        }
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return autoStartup;
    }

    private void runLoop() {
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                poller.pollAndDispatch(pollTimeout);
            }
        } catch (RuntimeException ex) {
            throw ex;
        } finally {
            running.set(false);
        }
    }
}
