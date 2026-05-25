package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.messaging.TradingEventBus;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class BinanceUserDataStreamRuntime implements AutoCloseable {

    private final BinanceProperties.UserDataStream userData;
    private final BinanceUserDataStreamClient userDataClient;
    private final BinanceWebSocketClient webSocketClient;
    private final BinanceWebSocketEndpointPlanner endpointPlanner;
    private final BinanceWebSocketReconnectScheduler scheduler;
    private final Clock clock;
    private final Duration reconnectDelay;
    private final BinanceUserDataEventMapper mapper;
    private final BinanceUserDataEventMapper.Context context;
    private final TradingEventBus eventBus;
    private final BinanceWebSocketListener delegate;
    private final Object lock = new Object();

    private BinanceUserDataStreamSession session;
    private BinanceWebSocketSupervisor supervisor;
    private BinanceScheduledTask renewalTask;
    private boolean stopped = true;

    BinanceUserDataStreamRuntime(
            BinanceProperties.UserDataStream userData,
            BinanceUserDataStreamClient userDataClient,
            BinanceWebSocketClient webSocketClient,
            BinanceWebSocketEndpointPlanner endpointPlanner,
            BinanceWebSocketReconnectScheduler scheduler,
            Clock clock,
            Duration reconnectDelay,
            BinanceUserDataEventMapper mapper,
            BinanceUserDataEventMapper.Context context,
            TradingEventBus eventBus,
            BinanceWebSocketListener delegate
    ) {
        this.userData = Objects.requireNonNull(userData, "userData");
        this.userDataClient = Objects.requireNonNull(userDataClient, "userDataClient");
        this.webSocketClient = Objects.requireNonNull(webSocketClient, "webSocketClient");
        this.endpointPlanner = Objects.requireNonNull(endpointPlanner, "endpointPlanner");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reconnectDelay = requireNonNegative(reconnectDelay, "reconnectDelay");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.context = Objects.requireNonNull(context, "context");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    BinanceUserDataStreamSession start() {
        synchronized (lock) {
            if (!stopped && session != null) {
                return session;
            }
            stopped = false;
            openNewSession();
            return session;
        }
    }

    Optional<BinanceUserDataStreamSession> activeSession() {
        synchronized (lock) {
            return Optional.ofNullable(session);
        }
    }

    Optional<BinanceWebSocketConnection> activeConnection() {
        synchronized (lock) {
            return supervisor == null ? Optional.empty() : supervisor.activeConnection();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            stopped = true;
            cancelRenewal();
            closeSupervisor();
            closeRestStream(session);
            session = null;
        }
    }

    private void renew() {
        synchronized (lock) {
            if (stopped || session == null) {
                return;
            }
            cancelRenewal();
            if (!hasText(userData.keepalivePath())) {
                closeSupervisor();
                closeRestStream(session);
                openNewSession();
                return;
            }
            BinanceUserDataStreamSession previous = session;
            BinanceUserDataStreamSession renewed = userDataClient.keepAlive(previous.streamId());
            session = renewed;
            if (!previous.streamId().equals(renewed.streamId())) {
                closeSupervisor();
                closeRestStream(previous);
                openSupervisor();
            }
            scheduleRenewal();
        }
    }

    private void openNewSession() {
        BinanceUserDataStreamSession started = userDataClient.start();
        try {
            session = started;
            openSupervisor();
            scheduleRenewal();
        } catch (RuntimeException e) {
            session = null;
            closeRestStream(started);
            throw e;
        }
    }

    private void openSupervisor() {
        supervisor = new BinanceWebSocketSupervisor(
                webSocketClient,
                this::connectionPlan,
                new BinanceUserDataEventPublisher(mapper, context, eventBus, delegate),
                scheduler,
                clock,
                reconnectDelay
        );
        supervisor.start();
    }

    private BinanceWebSocketConnectionPlan connectionPlan() {
        synchronized (lock) {
            if (session == null) {
                throw new IllegalStateException("Binance user-data stream session is not started");
            }
            return endpointPlanner.raw(BinanceWebSocketRoute.PRIVATE, List.of(session.streamId()));
        }
    }

    private void scheduleRenewal() {
        cancelRenewal();
        if (session == null) {
            return;
        }
        Duration delay = Duration.between(clock.instant(), session.renewAfter());
        renewalTask = scheduler.schedule(delay.isNegative() ? Duration.ZERO : delay, this::renew);
    }

    private void cancelRenewal() {
        if (renewalTask != null) {
            renewalTask.cancel();
            renewalTask = null;
        }
    }

    private void closeSupervisor() {
        if (supervisor != null) {
            supervisor.close();
            supervisor = null;
        }
    }

    private void closeRestStream(BinanceUserDataStreamSession streamSession) {
        if (streamSession != null && hasText(userData.closePath())) {
            userDataClient.close(streamSession.streamId());
        }
    }

    private Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must be zero or positive");
        }
        return value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
