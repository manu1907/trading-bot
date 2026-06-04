package io.github.manu.exchange.binance;

import io.github.manu.config.JsonMapperFactory;
import io.github.manu.config.properties.provider.binance.BinanceProperties;
import io.github.manu.config.properties.provider.binance.BinanceProviderProperties;
import io.github.manu.events.SerializedTradingEvent;
import io.github.manu.events.TradingEventCodec;
import io.github.manu.events.TradingEventType;
import io.github.manu.events.v1.BalanceUpdateEvent;
import io.github.manu.events.v1.OrderCommandAction;
import io.github.manu.events.v1.OrderCommandEvent;
import io.github.manu.events.v1.OrderResultEvent;
import io.github.manu.events.v1.OrderResultStatus;
import io.github.manu.events.v1.PositionUpdateEvent;
import io.github.manu.events.v1.RiskUpdateEvent;
import io.github.manu.events.TradingEventEnvelope;
import io.github.manu.events.TradingEventKeys;
import io.github.manu.exchange.ExchangeModule;
import io.github.manu.exchange.ResolvedExchangeConfig;
import io.github.manu.execution.OrderExecutionGateway;
import io.github.manu.journal.JournaledTradingEvent;
import io.github.manu.journal.TradingEventJournal;
import io.github.manu.messaging.TradingEventBus;
import io.github.manu.projection.TradingStateProjection;
import io.github.manu.reconciliation.ReconciliationConfidenceTracker;
import org.apache.avro.specific.SpecificRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Component
public class BinanceExchangeModule implements ExchangeModule, OrderExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(BinanceExchangeModule.class);

    private final ObjectProvider<TradingEventBus> eventBusProvider;
    private final ObjectProvider<TradingEventJournal> journalProvider;
    private final ObjectProvider<TradingStateProjection> projectionProvider;
    private final ObjectProvider<ReconciliationConfidenceTracker> reconciliationConfidenceTrackerProvider;
    private final ObjectProvider<BinanceExchangeMetadataService> metadataServiceProvider;
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
        this(null, null, null, null, null, Clock.systemUTC(), null, null);
    }

    @Autowired
    BinanceExchangeModule(
            ObjectProvider<TradingEventBus> eventBusProvider,
            ObjectProvider<TradingEventJournal> journalProvider,
            ObjectProvider<TradingStateProjection> projectionProvider,
            ObjectProvider<ReconciliationConfidenceTracker> reconciliationConfidenceTrackerProvider,
            ObjectProvider<BinanceExchangeMetadataService> metadataServiceProvider
    ) {
        this(
                eventBusProvider,
                journalProvider,
                projectionProvider,
                reconciliationConfidenceTrackerProvider,
                metadataServiceProvider,
                Clock.systemUTC(),
                null,
                null
        );
    }

    BinanceExchangeModule(ObjectProvider<TradingEventBus> eventBusProvider,
                          Clock clock,
                          BinanceHttpTransport httpTransportOverride,
                          BinanceWebSocketTransport webSocketTransportOverride) {
        this(eventBusProvider, null, null, null, null, clock, httpTransportOverride, webSocketTransportOverride);
    }

    BinanceExchangeModule(ObjectProvider<TradingEventBus> eventBusProvider,
                          ObjectProvider<TradingEventJournal> journalProvider,
                          ObjectProvider<TradingStateProjection> projectionProvider,
                          ObjectProvider<ReconciliationConfidenceTracker> reconciliationConfidenceTrackerProvider,
                          ObjectProvider<BinanceExchangeMetadataService> metadataServiceProvider,
                          Clock clock,
                          BinanceHttpTransport httpTransportOverride,
                          BinanceWebSocketTransport webSocketTransportOverride) {
        this.eventBusProvider = eventBusProvider;
        this.journalProvider = journalProvider;
        this.projectionProvider = projectionProvider;
        this.reconciliationConfidenceTrackerProvider = reconciliationConfidenceTrackerProvider;
        this.metadataServiceProvider = metadataServiceProvider;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.httpTransportOverride = httpTransportOverride;
        this.webSocketTransportOverride = webSocketTransportOverride;
    }

    BinanceExchangeModule(ObjectProvider<TradingEventBus> eventBusProvider,
                          ObjectProvider<TradingEventJournal> journalProvider,
                          Clock clock,
                          BinanceHttpTransport httpTransportOverride,
                          BinanceWebSocketTransport webSocketTransportOverride) {
        this(eventBusProvider, journalProvider, null, null, null, clock, httpTransportOverride, webSocketTransportOverride);
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
    public boolean supports(String provider, String environment, String account, String market) {
        if (!provider().equalsIgnoreCase(value(provider)) || config == null) {
            return false;
        }
        return same(config.target().environment(), environment)
                && same(config.target().account(), account)
                && same(config.target().market(), market);
    }

    @Override
    public CompletableFuture<TradingEventEnvelope<OrderResultEvent>> submit(OrderCommandEvent command) {
        Objects.requireNonNull(command, "command");
        ensureConfigured();
        if (!supports(
                value(command.getProvider()),
                value(command.getEnvironment()),
                value(command.getAccount()),
                value(command.getMarket())
        )) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Binance module is not configured for order target: "
                            + command.getProvider() + "/" + command.getEnvironment() + "/"
                            + command.getAccount() + "/" + command.getMarket()
            ));
        }
        BinanceProperties binance = requireBinance(config);
        BinanceHttpTransport httpTransport = httpTransportOverride == null
                ? new BinanceJdkHttpTransport(
                Duration.ofMillis(binance.rest().connectTimeoutMillis()),
                Duration.ofMillis(binance.rest().responseTimeoutMillis())
        )
                : httpTransportOverride;
        BinanceOrderResult result;
        try {
            BinanceOrderClient orderClient = new BinanceOrderClient(
                    binance,
                    binance.credentials().apiKey(),
                    binance.credentials().apiSecret(),
                    clock,
                    0L,
                    httpTransport,
                    JsonMapperFactory.create(),
                    new BinanceRateLimitTracker(clock),
                    resolveExchangeMetadata(binance),
                    referencePriceProvider(binance, httpTransport)
            );
            result = executeOrderCommand(orderClient, command, binance);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(toEnvelope(command, null, "VALIDATION", e.getMessage(), OrderResultStatus.REJECTED));
        } catch (BinanceApiException e) {
            OrderResultStatus status = new BinanceRetryPolicy(binance.rest())
                    .decide(e.httpStatusCode(), e.exchangeMessage())
                    .reconcileBeforeRetry()
                    ? OrderResultStatus.UNKNOWN
                    : OrderResultStatus.REJECTED;
            String rejectCode = e.exchangeCode() == null ? "HTTP_" + e.httpStatusCode() : e.exchangeCode().toString();
            return CompletableFuture.completedFuture(toEnvelope(command, null, rejectCode, e.exchangeMessage(), status));
        }
        return CompletableFuture.completedFuture(toEnvelope(command, result, null, null, null));
    }

    private BinanceOrderResult executeOrderCommand(
            BinanceOrderClient orderClient,
            OrderCommandEvent command,
            BinanceProperties binance
    ) {
        return switch (action(command)) {
            case NEW -> orderClient.placeOrder(toBinanceOrderCommand(command, binance));
            case CANCEL -> orderClient.cancelOrder(
                    value(command.getSymbol()),
                    optionalTargetExchangeOrderId(command),
                    optionalTargetClientOrderId(command)
            );
            case MODIFY -> orderClient.modifyOrder(toBinanceModifyOrderCommand(command));
        };
    }

    private OrderCommandAction action(OrderCommandEvent command) {
        return command.getAction() == null ? OrderCommandAction.NEW : command.getAction();
    }

    private BinanceModifyOrderCommand toBinanceModifyOrderCommand(OrderCommandEvent command) {
        Map<CharSequence, CharSequence> attributes = command.getAttributes() == null ? Map.of() : command.getAttributes();
        return new BinanceModifyOrderCommand(
                value(command.getSymbol()),
                optionalTargetExchangeOrderId(command),
                optionalTargetClientOrderId(command),
                command.getSide() == null ? null : command.getSide().name(),
                decimal(command.getQuantity()),
                decimal(command.getPrice()),
                attribute(attributes, null, "price_match", "priceMatch")
        );
    }

    private Long optionalTargetExchangeOrderId(OrderCommandEvent command) {
        String target = value(command.getTargetExchangeOrderId());
        if (target == null) {
            Map<CharSequence, CharSequence> attributes = command.getAttributes() == null ? Map.of() : command.getAttributes();
            target = attribute(attributes, null, "target_exchange_order_id", "orderId");
        }
        if (target == null) {
            return null;
        }
        return Long.valueOf(target);
    }

    private String optionalTargetClientOrderId(OrderCommandEvent command) {
        String target = value(command.getTargetClientOrderId());
        if (target != null) {
            return target;
        }
        Map<CharSequence, CharSequence> attributes = command.getAttributes() == null ? Map.of() : command.getAttributes();
        return attribute(attributes, null, "target_client_order_id", "origClientOrderId");
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
        if (requiresOrderSnapshots(reconciliation)) {
            BinanceOrderClient orderClient = new BinanceOrderClient(
                    binance,
                    binance.credentials().apiKey(),
                    binance.credentials().apiSecret(),
                    clock,
                    0L,
                    httpTransport,
                    JsonMapperFactory.create()
            );
            orderSnapshots = new BinanceRestSnapshotReconciliationRuntime.OrderSnapshots() {
                @Override
                public List<BinanceOrderResult> openOrders(String symbol) {
                    return orderClient.openOrders(symbol);
                }

                @Override
                public List<BinanceOrderResult> allOrders(BinanceOrderHistoryQuery query) {
                    return orderClient.allOrders(query);
                }

                @Override
                public List<BinanceAccountTrade> accountTrades(BinanceTradeHistoryQuery query) {
                    return orderClient.accountTrades(query);
                }
            };
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
        BinanceRestSnapshotReconciliationRuntime.OptionsSnapshots optionsSnapshots = null;
        if (requiresOptionsSnapshots(reconciliation)) {
            BinanceOptionsAccountClient optionsAccountClient = new BinanceOptionsAccountClient(
                    binance,
                    binance.credentials().apiKey(),
                    binance.credentials().apiSecret(),
                    clock,
                    0L,
                    httpTransport,
                    JsonMapperFactory.create()
            );
            optionsSnapshots = new BinanceRestSnapshotReconciliationRuntime.OptionsSnapshots() {
                @Override
                public BinanceOptionsMarginAccountSnapshot marginAccount() {
                    return optionsAccountClient.marginAccount();
                }

                @Override
                public List<BinanceOptionsPositionSnapshot> positions(String symbol) {
                    return optionsAccountClient.positions(symbol);
                }
            };
        }
        BinanceRestSnapshotReconciliationRuntime runtime = new BinanceRestSnapshotReconciliationRuntime(
                reconciliation,
                orderSnapshots,
                futuresSnapshots,
                marginSnapshots,
                optionsSnapshots,
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
                projectionComparator(reconciliation),
                reconciliationConfidenceTracker(reconciliation),
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

    private boolean requiresOptionsSnapshots(BinanceProperties.Reconciliation reconciliation) {
        return Boolean.TRUE.equals(reconciliation.optionsAccountEnabled())
                || Boolean.TRUE.equals(reconciliation.optionsPositionsEnabled());
    }

    private boolean requiresOrderSnapshots(BinanceProperties.Reconciliation reconciliation) {
        return Boolean.TRUE.equals(reconciliation.openOrdersEnabled())
                || Boolean.TRUE.equals(reconciliation.orderHistoryEnabled())
                || Boolean.TRUE.equals(reconciliation.accountTradesEnabled());
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

    private BinanceRestSnapshotProjectionComparator projectionComparator(
            BinanceProperties.Reconciliation reconciliation
    ) {
        if (!Boolean.TRUE.equals(reconciliation.projectionComparisonEnabled())) {
            return null;
        }
        TradingStateProjection projection = projectionProvider == null ? null : projectionProvider.getIfAvailable();
        if (projection == null) {
            log.warn("Binance reconciliation projection comparison is enabled but no TradingStateProjection is available");
            return null;
        }
        return new BinanceRestSnapshotProjectionComparator(projection);
    }

    private ReconciliationConfidenceTracker reconciliationConfidenceTracker(
            BinanceProperties.Reconciliation reconciliation
    ) {
        if (!Boolean.TRUE.equals(reconciliation.projectionComparisonEnabled())
                || reconciliationConfidenceTrackerProvider == null) {
            return null;
        }
        return reconciliationConfidenceTrackerProvider.getIfAvailable();
    }

    private BinanceOrderCommand toBinanceOrderCommand(OrderCommandEvent command, BinanceProperties binance) {
        Map<CharSequence, CharSequence> attributes = command.getAttributes() == null ? Map.of() : command.getAttributes();
        return new BinanceOrderCommand(
                value(command.getSymbol()),
                command.getSide() == null ? null : command.getSide().name(),
                command.getOrderType() == null ? null : command.getOrderType().name(),
                command.getTimeInForce() == null ? null : command.getTimeInForce().name(),
                command.getPositionSide() == null ? null : command.getPositionSide().name(),
                attribute(attributes, binance.rest().orderResponseTypeDefault(), "order_response_type", "newOrderRespType"),
                attribute(attributes, null, "self_trade_prevention_mode", "selfTradePreventionMode"),
                attribute(attributes, null, "side_effect_type", "sideEffectType"),
                attribute(attributes, null, "price_match", "priceMatch"),
                attribute(attributes, null, "working_type", "workingType"),
                attribute(attributes, null, "peg_price_type", "pegPriceType"),
                attribute(attributes, null, "peg_offset_type", "pegOffsetType"),
                integerAttribute(attributes, "peg_offset_value", "pegOffsetValue"),
                value(command.getClientOrderId()),
                longAttribute(attributes, "good_till_date", "goodTillDate"),
                decimal(command.getQuantity()),
                decimal(command.getQuoteOrderQuantity()),
                decimal(command.getPrice()),
                decimal(command.getStopPrice()),
                decimalAttribute(attributes, "trailing_delta", "trailingDelta"),
                decimal(command.getCallbackRate()),
                decimal(command.getActivationPrice()),
                decimalAttribute(attributes, "iceberg_qty", "icebergQty"),
                command.getReduceOnly(),
                command.getClosePosition(),
                booleanAttribute(attributes, "price_protect", "priceProtect"),
                booleanAttribute(attributes, "auto_repay_at_cancel", "autoRepayAtCancel"),
                booleanAttribute(attributes, "isolated_margin", "isIsolated"),
                booleanAttribute(attributes, "market_maker_protection", "marketMakerProtection"),
                booleanAttribute(attributes, "post_only", "postOnly")
        );
    }

    private BinanceExchangeMetadata resolveExchangeMetadata(BinanceProperties binance) {
        if (!binance.trading().enforceExchangeFilters()) {
            return null;
        }
        BinanceExchangeMetadataService service = metadataServiceProvider == null
                ? null
                : metadataServiceProvider.getIfAvailable();
        if (service == null) {
            throw new IllegalArgumentException("exchangeInfo metadata service is required for Binance exchange-filter validation");
        }
        return service.current()
                .filter(metadataSnapshot -> same(binance.rest().baseUrl(), metadataSnapshot.restBaseUrl()))
                .or(() -> service.refresh(config)
                        .filter(metadataSnapshot -> same(binance.rest().baseUrl(), metadataSnapshot.restBaseUrl())))
                .orElseThrow(() -> new IllegalArgumentException(
                        "exchangeInfo metadata is required for Binance exchange-filter validation"));
    }

    private BinanceReferencePriceProvider referencePriceProvider(
            BinanceProperties binance,
            BinanceHttpTransport httpTransport
    ) {
        if (!binance.trading().enforcePercentPriceFilters()) {
            return null;
        }
        return new BinanceRestReferencePriceProvider(binance, httpTransport, JsonMapperFactory.create(), clock);
    }

    private TradingEventEnvelope<OrderResultEvent> toEnvelope(
            OrderCommandEvent command,
            BinanceOrderResult result,
            String rejectCode,
            String rejectMessage,
            OrderResultStatus fallbackStatus
    ) {
        OrderResultEvent event = orderResultEvent(command, result, rejectCode, rejectMessage, fallbackStatus);
        return TradingEventEnvelope.of(
                TradingEventType.ORDER_RESULT,
                TradingEventKeys.order(
                        TradingEventType.ORDER_RESULT,
                        value(command.getProvider()),
                        value(command.getEnvironment()),
                        value(command.getAccount()),
                        value(command.getMarket()),
                        value(event.getSymbol()),
                        value(event.getClientOrderId())
                ),
                event
        );
    }

    private OrderResultEvent orderResultEvent(
            OrderCommandEvent command,
            BinanceOrderResult result,
            String rejectCode,
            String rejectMessage,
            OrderResultStatus fallbackStatus
    ) {
        boolean rejected = result == null;
        Map<CharSequence, CharSequence> attributes = new LinkedHashMap<>();
        attributes.put("source", "binance_order_gateway");
        if (rejected) {
            attributes.put("http_reject", "true");
        }
        String exchangeStatus = rejected ? null : result.status();
        return OrderResultEvent.newBuilder()
                .setEventId("order-result:" + value(command.getCommandId()) + ":" + value(command.getClientOrderId()))
                .setSchemaVersion(1)
                .setCommandId(value(command.getCommandId()))
                .setProvider(value(command.getProvider()))
                .setEnvironment(value(command.getEnvironment()))
                .setAccount(value(command.getAccount()))
                .setMarket(value(command.getMarket()))
                .setSymbol(result == null || !hasText(result.symbol()) ? value(command.getSymbol()) : result.symbol())
                .setClientOrderId(result == null || !hasText(result.clientOrderId()) ? value(command.getClientOrderId()) : result.clientOrderId())
                .setExchangeOrderId(result == null || result.orderId() == null ? null : result.orderId().toString())
                .setStatus(result == null ? fallbackStatus : status(exchangeStatus))
                .setExchangeStatus(exchangeStatus)
                .setPrice(decimalString(result == null ? decimal(command.getPrice()) : result.price()))
                .setOriginalQuantity(decimalString(result == null ? decimal(command.getQuantity()) : result.originalQuantity()))
                .setExecutedQuantity(decimalString(result == null ? null : result.executedQuantity()))
                .setAveragePrice(decimalString(result == null ? null : result.averagePrice()))
                .setCumulativeQuote(decimalString(result == null ? null : result.cumulativeQuote()))
                .setExchangeTransactTimeMicros(result == null || result.updateTime() == null ? null : Instant.ofEpochMilli(result.updateTime()))
                .setObservedAtMicros(clock.instant())
                .setRejectCode(rejectCode)
                .setRejectMessage(rejectMessage)
                .setAttributes(Map.copyOf(attributes))
                .build();
    }

    private OrderResultStatus status(String value) {
        if (!hasText(value)) {
            return OrderResultStatus.UNKNOWN;
        }
        return switch (value) {
            case "NEW" -> OrderResultStatus.ACCEPTED;
            case "PARTIALLY_FILLED" -> OrderResultStatus.PARTIALLY_FILLED;
            case "FILLED" -> OrderResultStatus.FILLED;
            case "CANCELED" -> OrderResultStatus.CANCELED;
            case "EXPIRED" -> OrderResultStatus.EXPIRED;
            case "REJECTED" -> OrderResultStatus.REJECTED;
            default -> OrderResultStatus.UNKNOWN;
        };
    }

    private BigDecimal decimal(CharSequence value) {
        String text = value(value);
        return text == null ? null : new BigDecimal(text);
    }

    private BigDecimal decimalAttribute(Map<CharSequence, CharSequence> attributes, String... names) {
        return decimal(attribute(attributes, null, names));
    }

    private Integer integerAttribute(Map<CharSequence, CharSequence> attributes, String... names) {
        String value = attribute(attributes, null, names);
        return value == null ? null : Integer.valueOf(value);
    }

    private Long longAttribute(Map<CharSequence, CharSequence> attributes, String... names) {
        String value = attribute(attributes, null, names);
        return value == null ? null : Long.valueOf(value);
    }

    private Boolean booleanAttribute(Map<CharSequence, CharSequence> attributes, String... names) {
        String value = attribute(attributes, null, names);
        return value == null ? null : Boolean.valueOf(value);
    }

    private String attribute(Map<CharSequence, CharSequence> attributes, String defaultValue, String... names) {
        for (String name : names) {
            for (Map.Entry<CharSequence, CharSequence> entry : attributes.entrySet()) {
                if (name.contentEquals(entry.getKey())) {
                    String value = value(entry.getValue());
                    return value == null ? defaultValue : value;
                }
            }
        }
        return defaultValue;
    }

    private String decimalString(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String reconciliationEventId(SerializedTradingEvent serialized) {
        TradingEventType eventType = serialized.eventType();
        if (eventType != TradingEventType.ORDER_RESULT
                && eventType != TradingEventType.BALANCE_UPDATE
                && eventType != TradingEventType.POSITION_UPDATE
                && eventType != TradingEventType.RISK_UPDATE) {
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
        if (value instanceof RiskUpdateEvent event && restSnapshot(event.getAttributes())) {
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

    private String value(CharSequence value) {
        if (!hasText(value)) {
            return null;
        }
        return value.toString().trim();
    }

    private boolean same(String expected, String actual) {
        return expected != null && expected.equals(value(actual));
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
