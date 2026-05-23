package io.github.manu.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TradingEventConsumerLoopTest {

    @Test
    void starts_polls_and_stops_cleanly() throws InterruptedException {
        CountingPoller poller = new CountingPoller();
        TradingEventConsumerLoop loop = new TradingEventConsumerLoop(poller, Duration.ofMillis(10), false);

        loop.start();
        assertThat(poller.awaitPoll()).isTrue();
        loop.stop();

        assertThat(loop.isRunning()).isFalse();
        assertThat(poller.polls()).isPositive();
        assertThat(loop.isAutoStartup()).isFalse();
    }

    @Test
    void stops_running_state_after_poll_failure() throws InterruptedException {
        FailingPoller poller = new FailingPoller();
        TradingEventConsumerLoop loop = new TradingEventConsumerLoop(poller, Duration.ofMillis(10), true);

        loop.start();
        assertThat(poller.awaitPoll()).isTrue();
        waitUntilStopped(loop);

        assertThat(loop.isRunning()).isFalse();
        assertThat(loop.isAutoStartup()).isTrue();
    }

    private static void waitUntilStopped(TradingEventConsumerLoop loop) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (loop.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static final class CountingPoller implements TradingEventPoller {

        private final CountDownLatch firstPoll = new CountDownLatch(1);
        private final AtomicInteger polls = new AtomicInteger();

        @Override
        public List<TradingEventDispatchResult> pollAndDispatch(Duration timeout) {
            polls.incrementAndGet();
            firstPoll.countDown();
            sleep(timeout);
            return List.of();
        }

        private boolean awaitPoll() throws InterruptedException {
            return firstPoll.await(2, TimeUnit.SECONDS);
        }

        private int polls() {
            return polls.get();
        }
    }

    private static final class FailingPoller implements TradingEventPoller {

        private final CountDownLatch firstPoll = new CountDownLatch(1);

        @Override
        public List<TradingEventDispatchResult> pollAndDispatch(Duration timeout) {
            firstPoll.countDown();
            throw new MessagingException("poll failed");
        }

        private boolean awaitPoll() throws InterruptedException {
            return firstPoll.await(2, TimeUnit.SECONDS);
        }
    }

    private static void sleep(Duration timeout) {
        try {
            Thread.sleep(timeout.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
