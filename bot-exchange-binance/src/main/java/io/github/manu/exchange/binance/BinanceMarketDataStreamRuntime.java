package io.github.manu.exchange.binance;

import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.messaging.TradingEventBus;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

final class BinanceMarketDataStreamRuntime implements AutoCloseable {

    private final BinanceProperties.MarketDataStream marketData;
    private final BinanceWebSocketClient webSocketClient;
    private final BinanceWebSocketEndpointPlanner endpointPlanner;
    private final BinanceWebSocketReconnectScheduler scheduler;
    private final Clock clock;
    private final Duration reconnectDelay;
    private final BinanceMarketDataEventMapper mapper;
    private final BinanceMarketDataEventMapper.Context context;
    private final TradingEventBus eventBus;
    private final BinanceWebSocketListener delegate;
    private final Object lock = new Object();

    private BinanceWebSocketSupervisor supervisor;
    private boolean stopped = true;

    BinanceMarketDataStreamRuntime(
            BinanceProperties.MarketDataStream marketData,
            BinanceWebSocketClient webSocketClient,
            BinanceWebSocketEndpointPlanner endpointPlanner,
            BinanceWebSocketReconnectScheduler scheduler,
            Clock clock,
            Duration reconnectDelay,
            BinanceMarketDataEventMapper mapper,
            BinanceMarketDataEventMapper.Context context,
            TradingEventBus eventBus,
            BinanceWebSocketListener delegate
    ) {
        this.marketData = Objects.requireNonNull(marketData, "marketData");
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

    void start() {
        synchronized (lock) {
            if (!stopped && supervisor != null) {
                return;
            }
            stopped = false;
            openSupervisor();
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
            closeSupervisor();
        }
    }

    private void openSupervisor() {
        supervisor = new BinanceWebSocketSupervisor(
                webSocketClient,
                this::connectionPlan,
                new BinanceMarketDataEventPublisher(mapper, context, eventBus, delegate, clock),
                scheduler,
                clock,
                reconnectDelay
        );
        supervisor.start();
    }

    private BinanceWebSocketConnectionPlan connectionPlan() {
        List<String> streams = marketData.streams();
        if (streams.isEmpty()) {
            throw new IllegalStateException("Binance market_data.streams must not be empty when runtime is enabled");
        }
        BinanceWebSocketRoute route = route();
        return switch (mode()) {
            case RAW -> endpointPlanner.raw(route, streams);
            case COMBINED -> endpointPlanner.combined(route, streams);
        };
    }

    private BinanceWebSocketMode mode() {
        String value = requireText(marketData.connectionMode(), "market_data.connection_mode");
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "raw" -> BinanceWebSocketMode.RAW;
            case "combined" -> BinanceWebSocketMode.COMBINED;
            default -> throw new IllegalArgumentException("Unsupported Binance market_data.connection_mode: " + value);
        };
    }

    private BinanceWebSocketRoute route() {
        String value = requireText(marketData.route(), "market_data.route");
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "default" -> BinanceWebSocketRoute.DEFAULT;
            case "public" -> BinanceWebSocketRoute.PUBLIC;
            case "market" -> BinanceWebSocketRoute.MARKET;
            default -> throw new IllegalArgumentException("Unsupported Binance market_data.route: " + value);
        };
    }

    private void closeSupervisor() {
        if (supervisor != null) {
            supervisor.close();
            supervisor = null;
        }
    }

    private Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must be zero or positive");
        }
        return value;
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
