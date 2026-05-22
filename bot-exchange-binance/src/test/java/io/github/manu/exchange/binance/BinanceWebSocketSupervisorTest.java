package io.github.manu.exchange.binance;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceWebSocketSupervisorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-22T20:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void opens_connection_and_rolls_over_at_planned_reconnect_time() {
        FakeTransport transport = new FakeTransport();
        FakeScheduler scheduler = new FakeScheduler();
        RecordingListener listener = new RecordingListener();
        BinanceWebSocketSupervisor supervisor = supervisor(transport, scheduler, listener, plans(2));

        supervisor.start();

        assertThat(listener.openedPlans).containsExactly(plan(1));
        assertThat(scheduler.tasks).hasSize(1);
        assertThat(scheduler.tasks.getFirst().delay).isEqualTo(Duration.ofHours(23).plusMinutes(50));

        scheduler.tasks.removeFirst().run();

        assertThat(listener.openedPlans).containsExactly(plan(1), plan(2));
        assertThat(listener.closeCount).hasValue(0);
        assertThat(transport.closedGenerations).containsExactly(1);
        assertThat(supervisor.activeConnection()).isPresent();
    }

    @Test
    void schedules_reconnect_after_active_connection_error() {
        FakeTransport transport = new FakeTransport();
        FakeScheduler scheduler = new FakeScheduler();
        RecordingListener listener = new RecordingListener();
        BinanceWebSocketSupervisor supervisor = supervisor(transport, scheduler, listener, plans(2));

        supervisor.start();
        transport.listeners.getFirst().onError(new IllegalStateException("socket failed"));

        assertThat(listener.errors).hasSize(1);
        assertThat(scheduler.tasks).hasSize(2);
        assertThat(scheduler.tasks.get(1).delay).isEqualTo(Duration.ofSeconds(2));

        scheduler.tasks.get(1).run();

        assertThat(listener.openedPlans).containsExactly(plan(1), plan(2));
        assertThat(transport.closedGenerations).containsExactly(1);
    }

    @Test
    void retries_when_initial_connect_fails() {
        FakeTransport transport = new FakeTransport();
        transport.failConnects = 1;
        FakeScheduler scheduler = new FakeScheduler();
        RecordingListener listener = new RecordingListener();
        BinanceWebSocketSupervisor supervisor = supervisor(transport, scheduler, listener, plans(2));

        supervisor.start();

        assertThat(listener.openedPlans).isEmpty();
        assertThat(listener.errors).hasSize(1);
        assertThat(scheduler.tasks).hasSize(1);
        assertThat(scheduler.tasks.getFirst().delay).isEqualTo(Duration.ofSeconds(2));

        scheduler.tasks.removeFirst().run();

        assertThat(listener.openedPlans).containsExactly(plan(2));
    }

    @Test
    void close_cancels_tasks_and_does_not_schedule_reconnect() {
        FakeTransport transport = new FakeTransport();
        FakeScheduler scheduler = new FakeScheduler();
        RecordingListener listener = new RecordingListener();
        BinanceWebSocketSupervisor supervisor = supervisor(transport, scheduler, listener, plans(1));

        supervisor.start();
        supervisor.close();

        assertThat(supervisor.activeConnection()).isEmpty();
        assertThat(listener.closeCount).hasValue(0);
        assertThat(transport.closedGenerations).containsExactly(1);
        assertThat(scheduler.tasks.getFirst().cancelled).isTrue();

        transport.listeners.getFirst().onClose();

        assertThat(scheduler.tasks).hasSize(1);
    }

    @Test
    void rejects_negative_reconnect_delay() {
        assertThatThrownBy(() -> new BinanceWebSocketSupervisor(
                new BinanceWebSocketClient(new FakeTransport()),
                () -> plan(1),
                new RecordingListener(),
                new FakeScheduler(),
                FIXED_CLOCK,
                Duration.ofMillis(-1)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reconnectDelay");
    }

    private BinanceWebSocketSupervisor supervisor(
            FakeTransport transport,
            FakeScheduler scheduler,
            BinanceWebSocketListener listener,
            Supplier<BinanceWebSocketConnectionPlan> plans
    ) {
        return new BinanceWebSocketSupervisor(
                new BinanceWebSocketClient(transport),
                plans,
                listener,
                scheduler,
                FIXED_CLOCK,
                Duration.ofSeconds(2)
        );
    }

    private Supplier<BinanceWebSocketConnectionPlan> plans(int count) {
        Queue<BinanceWebSocketConnectionPlan> plans = new ArrayDeque<>();
        for (int index = 1; index <= count; index++) {
            plans.add(plan(index));
        }
        return plans::remove;
    }

    private static BinanceWebSocketConnectionPlan plan(int index) {
        Instant createdAt = Instant.parse("2026-05-22T20:00:00Z").plusSeconds(index);
        return new BinanceWebSocketConnectionPlan(
                URI.create("wss://fstream.binance.com/market/stream?streams=btcusdt@aggTrade&plan=" + index),
                BinanceWebSocketMode.COMBINED,
                BinanceWebSocketRoute.MARKET,
                List.of("btcusdt@aggTrade"),
                createdAt,
                createdAt.plus(Duration.ofHours(24)),
                Instant.parse("2026-05-23T19:50:00Z"),
                Duration.ofMinutes(3),
                Duration.ofMinutes(10),
                10
        );
    }

    private static final class FakeTransport implements BinanceWebSocketTransport {

        private final List<Integer> closedGenerations = new ArrayList<>();
        private final List<BinanceWebSocketListener> listeners = new ArrayList<>();
        private int generation;
        private int failConnects;

        @Override
        public BinanceWebSocketConnection connect(
                BinanceWebSocketConnectionPlan plan,
                BinanceWebSocketListener listener
        ) {
            if (failConnects > 0) {
                failConnects--;
                throw new IllegalStateException("connect failed");
            }
            generation++;
            int connectionGeneration = generation;
            listeners.add(listener);
            listener.onOpen(plan);
            return new BinanceWebSocketConnection(
                    plan,
                    FIXED_CLOCK.instant(),
                    () -> {
                        closedGenerations.add(connectionGeneration);
                        listener.onClose();
                    }
            );
        }
    }

    private static final class FakeScheduler implements BinanceWebSocketReconnectScheduler {

        private final List<ScheduledTask> tasks = new ArrayList<>();

        @Override
        public BinanceScheduledTask schedule(Duration delay, Runnable task) {
            ScheduledTask scheduled = new ScheduledTask(delay, task);
            tasks.add(scheduled);
            return scheduled;
        }
    }

    private static final class ScheduledTask implements BinanceScheduledTask {

        private final Duration delay;
        private final Runnable task;
        private boolean cancelled;

        private ScheduledTask(Duration delay, Runnable task) {
            this.delay = delay;
            this.task = task;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        private void run() {
            if (!cancelled) {
                task.run();
            }
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
