package io.github.manu.exchange.binance;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

final class BinanceWebSocketSupervisor implements AutoCloseable {

    private final BinanceWebSocketClient client;
    private final Supplier<BinanceWebSocketConnectionPlan> planSupplier;
    private final BinanceWebSocketListener listener;
    private final BinanceWebSocketReconnectScheduler scheduler;
    private final Clock clock;
    private final Duration reconnectDelay;
    private final Object lock = new Object();
    private final Set<Integer> suppressedCloseGenerations = new HashSet<>();

    private BinanceWebSocketConnection connection;
    private BinanceScheduledTask rolloverTask;
    private BinanceScheduledTask reconnectTask;
    private boolean stopped = true;
    private int nextGeneration = 1;
    private int activeGeneration;

    BinanceWebSocketSupervisor(
            BinanceWebSocketClient client,
            Supplier<BinanceWebSocketConnectionPlan> planSupplier,
            BinanceWebSocketListener listener,
            BinanceWebSocketReconnectScheduler scheduler,
            Clock clock,
            Duration reconnectDelay
    ) {
        this.client = Objects.requireNonNull(client, "client is required");
        this.planSupplier = Objects.requireNonNull(planSupplier, "planSupplier is required");
        this.listener = Objects.requireNonNull(listener, "listener is required");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.reconnectDelay = Objects.requireNonNull(reconnectDelay, "reconnectDelay is required");
        if (reconnectDelay.isNegative()) {
            throw new IllegalArgumentException("reconnectDelay must be zero or positive");
        }
    }

    void start() {
        synchronized (lock) {
            if (!stopped && connection != null && !connection.isClosed()) {
                return;
            }
            stopped = false;
            openConnection();
        }
    }

    Optional<BinanceWebSocketConnection> activeConnection() {
        synchronized (lock) {
            return Optional.ofNullable(connection);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            stopped = true;
            cancelReconnectTasks();
            closeActiveConnection();
        }
    }

    private void reconnect() {
        synchronized (lock) {
            if (stopped) {
                return;
            }
            cancelReconnectTasks();
            closeActiveConnection();
            openConnection();
        }
    }

    private void openConnection() {
        int generation = nextGeneration++;
        BinanceWebSocketConnectionPlan plan = planSupplier.get();
        try {
            BinanceWebSocketConnection opened = client.connect(plan, new ManagedListener(generation));
            connection = opened;
            activeGeneration = generation;
            scheduleRollover(opened.plan());
        } catch (RuntimeException e) {
            listener.onError(e);
            scheduleReconnect();
        }
    }

    private void scheduleRollover(BinanceWebSocketConnectionPlan plan) {
        cancelTask(rolloverTask);
        Duration delay = Duration.between(clock.instant(), plan.reconnectAt());
        rolloverTask = scheduler.schedule(delay.isNegative() ? Duration.ZERO : delay, this::reconnect);
    }

    private void scheduleReconnect() {
        synchronized (lock) {
            if (stopped) {
                return;
            }
            cancelTask(reconnectTask);
            reconnectTask = scheduler.schedule(reconnectDelay, this::reconnect);
        }
    }

    private void cancelReconnectTasks() {
        cancelTask(rolloverTask);
        cancelTask(reconnectTask);
        rolloverTask = null;
        reconnectTask = null;
    }

    private void cancelTask(BinanceScheduledTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private void closeActiveConnection() {
        if (connection != null && !connection.isClosed()) {
            suppressedCloseGenerations.add(activeGeneration);
            connection.close();
        }
        connection = null;
        activeGeneration = 0;
    }

    private boolean consumeSuppressedClose(int generation) {
        synchronized (lock) {
            return suppressedCloseGenerations.remove(generation);
        }
    }

    private boolean isActiveGeneration(int generation) {
        synchronized (lock) {
            return !stopped && activeGeneration == generation;
        }
    }

    private final class ManagedListener implements BinanceWebSocketListener {

        private final int generation;

        private ManagedListener(int generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(BinanceWebSocketConnectionPlan plan) {
            listener.onOpen(plan);
        }

        @Override
        public void onText(String text) {
            if (isActiveGeneration(generation)) {
                listener.onText(text);
            }
        }

        @Override
        public void onError(Throwable error) {
            if (isActiveGeneration(generation)) {
                listener.onError(error);
                scheduleReconnect();
            }
        }

        @Override
        public void onClose() {
            if (consumeSuppressedClose(generation)) {
                return;
            }
            if (isActiveGeneration(generation)) {
                listener.onClose();
                scheduleReconnect();
            }
        }
    }
}
