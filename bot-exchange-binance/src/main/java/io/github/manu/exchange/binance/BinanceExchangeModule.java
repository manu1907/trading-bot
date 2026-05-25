package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.config.properties.provider.binance.BinanceProviderProperties;
import io.github.manu.events.SerializedTradingEvent;
import io.github.manu.events.TradingEventCodec;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.exchange.ExchangeModule;
import io.github.manu.exchange.ResolvedExchangeConfig;
import io.github.manu.journal.JournaledTradingEvent;
import io.github.manu.journal.TradingEventJournal;
import io.github.manu.messaging.TradingEventBus;
import org.apache.avro.specific.SpecificRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Component
public class BinanceExchangeModule implements ExchangeModule {

    private static final Logger log = LoggerFactory.getLogger(BinanceExchangeModule.class);

    private final ObjectProvider<TradingEventBus> eventBusProvider;
    private final ObjectProvider<TradingEventJournal> journalProvider;
    private final Clock clock;
    private final BinanceHttpTransport httpTransportOverride;
    private final BinanceWebSocketTransport webSocketTransportOverride;

    private volatile ResolvedExchangeConfig config;
    private volatile boolean connected;
    private BinanceUserDataStreamRuntime userDataRuntime;
    private BinanceMarketDataStreamRuntime marketDataRuntime;
    private BinanceRestSnapshotReconciliationRuntime reconciliationRuntime;
    private ScheduledExecutorService userDataSchedulerExecutor;
    private ScheduledExecutorService marketDataSchedulerExecutor;
    private ScheduledExecutorService reconciliationSchedulerExecutor;

    public BinanceExchangeModule() {
        this(null, null, Clock.systemUTC(), null, null);
    }

    @Autowired
    BinanceExchangeModule(
            ObjectProvider<TradingEventBus> eventBusProvider,
            ObjectProvider<TradingEventJournal> journalProvider
    ) {
        this(eventBusProvider, journalProvider, Clock.systemUTC(), null, null);
    }

    BinanceExchangeModule(ObjectProvider<TradingEventBus> eventBusProvider,
                          Clock clock,
                          BinanceHttpTransport httpTransportOverride,
                          BinanceWebSocketTransport webSocketTransportOverride) {
        this(eventBusProvider, null, clock, httpTransportOverride, webSocketTransportOverride);
    }

    BinanceExchangeModule(ObjectProvider<TradingEventBus> eventBusProvider,
                          ObjectProvider<TradingEventJournal> journalProvider,
                          Clock clock,
                          BinanceHttpTransport httpTransportOverride,
                          BinanceWebSocketTransport webSocketTransportOverride) {
        this.eventBusProvider = eventBusProvider;
        this.journalProvider = journalProvider;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.httpTransportOverride = httpTransportOverride;
        this.webSocketTransportOverride = webSocketTransportOverride;
    }

    @Override
    public String provider() {
        return "binance";
    }

    @Override
    public void validateConfig(ResolvedExchangeConfig config) {
        BinanceProperties binance = requireBinance(config);
        BinanceConfigValidator.validate(config.target(), binance);
    }

    @Override
    public void configure(ResolvedExchangeConfig config) {
        BinanceProperties binance = requireBinance(config);
        this.config = config;
        log.info(
                "Configured Binance exchange module: environment={}, account={}, market={}, restBaseUrl={}, websocketBaseUrl={}",
                config.target().environment(),
                config.target().account(),
                config.target().market(),
                binance.rest().baseUrl(),
                binance.websocket().baseUrl()
        );
    }

    @Override
    public CompletableFuture<Void> connect() {
        ensureConfigured();
        if (connected) {
            return CompletableFuture.completedFuture(null);
        }
        BinanceProperties binance = requireBinance(config);
        try {
            startUserDataRuntimeIfEnabled(binance);
            startMarketDataRuntimeIfEnabled(binance);
            startReconciliationRuntimeIfEnabled(binance);
        } catch (RuntimeException e) {
            stopReconciliationRuntime();
            stopMarketDataRuntime();
            stopUserDataRuntime();
            throw e;
        }
        connected = true;
        log.info("Binance exchange module connected for target {}", config.target());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        stopMarketDataRuntime();
        stopReconciliationRuntime();
        stopUserDataRuntime();
        connected = false;
        log.info("Binance exchange module disconnected");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> applyMutableConfig(ResolvedExchangeConfig config) {
        boolean wasConnected = connected;
        if (wasConnected) {
            stopReconciliationRuntime();
            stopMarketDataRuntime();
            stopUserDataRuntime();
        }
        configure(config);
        if (wasConnected) {
            try {
                startUserDataRuntimeIfEnabled(requireBinance(config));
                startMarketDataRuntimeIfEnabled(requireBinance(config));
                startReconciliationRuntimeIfEnabled(requireBinance(config));
            } catch (RuntimeException e) {
                stopReconciliationRuntime();
                stopMarketDataRuntime();
                stopUserDataRuntime();
                connected = false;
                throw e;
            }
            log.info("Applied Binance runtime configuration update for target {}", config.target());
        }
        return CompletableFuture.completedFuture(null);
    }

    private void startUserDataRuntimeIfEnabled(BinanceProperties binance) {
        BinanceProperties.UserDataStream userData = binance.userDataStream();
        if (userData == null || !Boolean.TRUE.equals(userData.runtimeEnabled())) {
            return;
        }
        TradingEventBus eventBus = eventBusProvider == null ? null : eventBusProvider.getIfAvailable();
        if (eventBus == null) {
            throw new IllegalStateException("Binance user_data.runtime_enabled requires a TradingEventBus");
        }

        userDataSchedulerExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "binance-user-data-runtime");
            thread.setDaemon(true);
            return thread;
        });
        BinanceHttpTransport httpTransport = httpTransportOverride == null
                ? new BinanceJdkHttpTransport(
                Duration.ofMillis(binance.rest().connectTimeoutMillis()),
                Duration.ofMillis(binance.rest().responseTimeoutMillis())
        )
                : httpTransportOverride;
        BinanceWebSocketTransport webSocketTransport = webSocketTransportOverride == null
                ? new BinanceReactorNettyWebSocketTransport()
                : webSocketTransportOverride;
        BinanceUserDataStreamRuntime runtime = new BinanceUserDataStreamRuntime(
                userData,
                new BinanceUserDataStreamClient(
                        binance,
                        binance.credentials().apiKey(),
                        clock,
                        httpTransport,
                        JsonMapperFactory.create()
                ),
                new BinanceWebSocketClient(webSocketTransport),
                new BinanceWebSocketEndpointPlanner(binance.websocket(), clock),
                new BinanceExecutorWebSocketReconnectScheduler(userDataSchedulerExecutor),
                clock,
                Duration.ofMillis(binance.rest().retryBackoffMillis()),
                new BinanceUserDataEventMapper(),
                new BinanceUserDataEventMapper.Context(
                        provider(),
                        config.target().environment(),
                        config.target().account(),
                        config.target().market()
                ),
                eventBus,
                new LoggingUserDataListener()
        );
        try {
            runtime.start();
            userDataRuntime = runtime;
        } catch (RuntimeException e) {
            runtime.close();
            stopUserDataRuntime();
            throw e;
        }
        log.info("Started Binance user-data stream runtime for target {}", config.target());
    }

    private void startMarketDataRuntimeIfEnabled(BinanceProperties binance) {
        BinanceProperties.MarketDataStream marketData = binance.marketData();
        if (marketData == null || !Boolean.TRUE.equals(marketData.runtimeEnabled())) {
            return;
        }
        TradingEventBus eventBus = eventBusProvider == null ? null : eventBusProvider.getIfAvailable();
        if (eventBus == null) {
            throw new IllegalStateException("Binance market_data.runtime_enabled requires a TradingEventBus");
        }

        marketDataSchedulerExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "binance-market-data-runtime");
            thread.setDaemon(true);
            return thread;
        });
        BinanceWebSocketTransport webSocketTransport = webSocketTransportOverride == null
                ? new BinanceReactorNettyWebSocketTransport()
                : webSocketTransportOverride;
        BinanceMarketDataStreamRuntime runtime = new BinanceMarketDataStreamRuntime(
                marketData,
                new BinanceWebSocketClient(webSocketTransport),
                new BinanceWebSocketEndpointPlanner(binance.websocket(), clock),
                new BinanceExecutorWebSocketReconnectScheduler(marketDataSchedulerExecutor),
                clock,
                Duration.ofMillis(binance.rest().retryBackoffMillis()),
                new BinanceMarketDataEventMapper(),
                new BinanceMarketDataEventMapper.Context(
                        provider(),
                        config.target().environment(),
                        config.target().account(),
                        config.target().market()
                ),
                eventBus,
                new LoggingMarketDataListener()
        );
        try {
            runtime.start();
            marketDataRuntime = runtime;
        } catch (RuntimeException e) {
            runtime.close();
            stopMarketDataRuntime();
            throw e;
        }
        log.info("Started Binance market-data stream runtime for target {}", config.target());
    }

    private void startReconciliationRuntimeIfEnabled(BinanceProperties binance) {
        BinanceProperties.Reconciliation reconciliation = binance.reconciliation();
        if (reconciliation == null || !Boolean.TRUE.equals(reconciliation.runtimeEnabled())) {
            return;
        }
        TradingEventBus eventBus = eventBusProvider == null ? null : eventBusProvider.getIfAvailable();
        if (eventBus == null) {
            throw new IllegalStateException("Binance reconciliation.runtime_enabled requires a TradingEventBus");
        }

        reconciliationSchedulerExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "binance-rest-reconciliation-runtime");
            thread.setDaemon(true);
            return thread;
        });
        BinanceHttpTransport httpTransport = httpTransportOverride == null
                ? new BinanceJdkHttpTransport(
                Duration.ofMillis(binance.rest().connectTimeoutMillis()),
                Duration.ofMillis(binance.rest().responseTimeoutMillis())
        )
                : httpTransportOverride;
        BinanceRestSnapshotReconciliationRuntime.OrderSnapshots orderSnapshots = null;
        if (Boolean.TRUE.equals(reconciliation.openOrdersEnabled())) {
            BinanceOrderClient orderClient = new BinanceOrderClient(
                    binance,
                    binance.credentials().apiKey(),
                    binance.credentials().apiSecret(),
                    clock,
                    0L,
                    httpTransport,
                    JsonMapperFactory.create()
            );
            orderSnapshots = orderClient::openOrders;
        }
        BinanceRestSnapshotReconciliationRuntime.FuturesSnapshots futuresSnapshots = null;
        if (requiresFuturesSnapshots(reconciliation)) {
            BinanceFuturesAccountClient futuresAccountClient = new BinanceFuturesAccountClient(
                    binance,
                    binance.credentials().apiKey(),
                    binance.credentials().apiSecret(),
                    clock,
                    0L,
                    httpTransport,
                    JsonMapperFactory.create()
            );
            futuresSnapshots = new BinanceRestSnapshotReconciliationRuntime.FuturesSnapshots() {
                @Override
                public List<BinanceFuturesBalance> balances() {
                    return futuresAccountClient.balances();
                }

                @Override
                public BinanceFuturesAccountSnapshot accountInfo() {
                    return futuresAccountClient.accountInfo();
                }

                @Override
                public List<BinanceFuturesPositionSnapshot> positionRisk(BinanceFuturesPositionRiskQuery query) {
                    return futuresAccountClient.positionRisk(query);
                }
            };
        }
        BinanceRestSnapshotReconciliationRuntime.MarginSnapshots marginSnapshots = null;
        if (requiresMarginSnapshots(reconciliation)) {
            BinanceMarginAccountClient marginAccountClient = new BinanceMarginAccountClient(
                    binance,
                    binance.credentials().apiKey(),
                    binance.credentials().apiSecret(),
                    clock,
                    0L,
                    httpTransport,
                    JsonMapperFactory.create(),
                    new BinanceRateLimitTracker(clock)
            );
            marginSnapshots = new BinanceRestSnapshotReconciliationRuntime.MarginSnapshots() {
                @Override
                public BinanceCrossMarginAccountSnapshot crossAccount() {
                    return marginAccountClient.crossAccount();
                }

                @Override
                public BinanceIsolatedMarginAccountSnapshot isolatedAccount(BinanceIsolatedMarginAccountQuery query) {
                    return marginAccountClient.isolatedAccount(query);
                }
            };
        }
        BinanceRestSnapshotReconciliationRuntime runtime = new BinanceRestSnapshotReconciliationRuntime(
                reconciliation,
                orderSnapshots,
                futuresSnapshots,
                marginSnapshots,
                new BinanceRestSnapshotEventPublisher(
                        new BinanceRestSnapshotEventMapper(),
                        new BinanceRestSnapshotEventPublisher.Context(
                                provider(),
                                config.target().environment(),
                                config.target().account(),
                                config.target().market()
                        ),
                        eventBus,
                        clock
                ),
                reconciliationSchedulerExecutor,
                recentReconciliationEventIds(reconciliation)
        );
        try {
            runtime.start();
            reconciliationRuntime = runtime;
        } catch (RuntimeException e) {
            runtime.close();
            stopReconciliationRuntime();
            throw e;
        }
        log.info("Started Binance REST snapshot reconciliation runtime for target {}", config.target());
    }

    private boolean requiresFuturesSnapshots(BinanceProperties.Reconciliation reconciliation) {
        return Boolean.TRUE.equals(reconciliation.futuresBalancesEnabled())
                || Boolean.TRUE.equals(reconciliation.futuresAccountEnabled())
                || Boolean.TRUE.equals(reconciliation.futuresPositionsEnabled());
    }

    private boolean requiresMarginSnapshots(BinanceProperties.Reconciliation reconciliation) {
        return Boolean.TRUE.equals(reconciliation.crossMarginAccountEnabled())
                || Boolean.TRUE.equals(reconciliation.isolatedMarginAccountEnabled());
    }

    private List<String> recentReconciliationEventIds(BinanceProperties.Reconciliation reconciliation) {
        if (journalProvider == null) {
            return List.of();
        }
        TradingEventJournal journal = journalProvider.getIfAvailable();
        if (journal == null) {
            return List.of();
        }

        List<String> eventIds = new ArrayList<>();
        for (JournaledTradingEvent journaled : journal.readAll()) {
            String eventId = reconciliationEventId(journaled.event());
            if (eventId != null) {
                eventIds.add(eventId);
            }
        }
        int window = reconciliation.dedupeWindowEventIds();
        if (eventIds.size() <= window) {
            return List.copyOf(eventIds);
        }
        return List.copyOf(eventIds.subList(eventIds.size() - window, eventIds.size()));
    }

    private String reconciliationEventId(SerializedTradingEvent serialized) {
        TradingEventType eventType = serialized.eventType();
        if (eventType != TradingEventType.ORDER_RESULT
                && eventType != TradingEventType.BALANCE_UPDATE
                && eventType != TradingEventType.POSITION_UPDATE) {
            return null;
        }
        SpecificRecord value = TradingEventCodec.<SpecificRecord>of(eventType.avroSchema())
                .decode(serialized.valuePayload());
        if (value instanceof OrderResultEvent event
                && hasText(event.getCommandId())
                && event.getCommandId().toString().startsWith("reconciliation:")) {
            return event.getEventId().toString();
        }
        if (value instanceof BalanceUpdateEvent event && restSnapshot(event.getAttributes())) {
            return event.getEventId().toString();
        }
        if (value instanceof PositionUpdateEvent event && restSnapshot(event.getAttributes())) {
            return event.getEventId().toString();
        }
        return null;
    }

    private boolean restSnapshot(Map<CharSequence, CharSequence> attributes) {
        if (attributes == null) {
            return false;
        }
        for (Map.Entry<CharSequence, CharSequence> entry : attributes.entrySet()) {
            if ("source".contentEquals(entry.getKey())
                    && entry.getValue() != null
                    && "rest_snapshot".contentEquals(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(CharSequence value) {
        return value != null && !value.toString().isBlank();
    }

    private void stopUserDataRuntime() {
        if (userDataRuntime != null) {
            userDataRuntime.close();
            userDataRuntime = null;
        }
        if (userDataSchedulerExecutor != null) {
            userDataSchedulerExecutor.shutdownNow();
            userDataSchedulerExecutor = null;
        }
    }

    private void stopMarketDataRuntime() {
        if (marketDataRuntime != null) {
            marketDataRuntime.close();
            marketDataRuntime = null;
        }
        if (marketDataSchedulerExecutor != null) {
            marketDataSchedulerExecutor.shutdownNow();
            marketDataSchedulerExecutor = null;
        }
    }

    private void stopReconciliationRuntime() {
        if (reconciliationRuntime != null) {
            reconciliationRuntime.close();
            reconciliationRuntime = null;
        }
        if (reconciliationSchedulerExecutor != null) {
            reconciliationSchedulerExecutor.shutdownNow();
            reconciliationSchedulerExecutor = null;
        }
    }

    private BinanceProperties requireBinance(ResolvedExchangeConfig config) {
        if (!provider().equalsIgnoreCase(config.provider())) {
            throw new IllegalArgumentException("Binance module cannot handle provider: " + config.provider());
        }
        return config.providerConfig(BinanceProviderProperties.class).resolve(config.target());
    }

    private void ensureConfigured() {
        if (config == null) {
            throw new IllegalStateException("Binance exchange module is not configured");
        }
    }

    private final class LoggingUserDataListener implements BinanceWebSocketListener {

        @Override
        public void onOpen(BinanceWebSocketConnectionPlan plan) {
            log.info("Opened Binance user-data websocket for target {}", config.target());
        }

        @Override
        public void onError(Throwable error) {
            log.warn("Binance user-data websocket reported an error for target {}", config.target(), error);
        }

        @Override
        public void onClose() {
            log.info("Closed Binance user-data websocket for target {}", config.target());
        }
    }

    private final class LoggingMarketDataListener implements BinanceWebSocketListener {

        @Override
        public void onOpen(BinanceWebSocketConnectionPlan plan) {
            log.info("Opened Binance market-data websocket for target {}", config.target());
        }

        @Override
        public void onError(Throwable error) {
            log.warn("Binance market-data websocket reported an error for target {}", config.target(), error);
        }

        @Override
        public void onClose() {
            log.info("Closed Binance market-data websocket for target {}", config.target());
        }
    }
}
